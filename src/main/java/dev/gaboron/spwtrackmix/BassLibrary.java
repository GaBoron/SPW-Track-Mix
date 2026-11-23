package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.DoubleConsumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class BassLibrary {
    private static final int BASS_2_4_18_3 = 0x02041203;
    private static final int POS_DSP = 0x800000;
    private static final int SAMPLE_8BIT = 0x1;
    private static final int SAMPLE_FLOAT = 0x100;
    private static final int STREAM_DECODE = 0x200000;
    private static final int STREAM_PRESCAN = 0x20000;
    private static final int UNICODE = 0x80000000;
    private static final int CTYPE_DSD = 0x11700;
    private static final int DSP_FLOAT = 2;
    private static final int DSP_FREECALL = 4;
    static final FunctionDescriptor DSP_PROC =
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle channelInfo;
    private final MethodHandle channelLength;
    private final MethodHandle channelPosition;
    private final MethodHandle addDsp;
    private final MethodHandle freeDsp;
    private final MethodHandle openFile;
    private final MethodHandle readData;
    private final MethodHandle freeStream;

    static BassLibrary resolveLoaded(Path dll) {
        // Resolve the existing module to keep host and plugin handles in the same engine.
        var kernel = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        var getModule = bind(kernel, "GetModuleHandleW", FunctionDescriptor.of(ADDRESS, ADDRESS));
        var getExport = bind(kernel, "GetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try (var arena = Arena.ofConfined()) {
            MemorySegment module = (MemorySegment) getModule.invokeExact(
                    arena.allocateFrom(dll.toString(), StandardCharsets.UTF_16LE));
            if (module.address() == 0) {
                throw new IllegalStateException("SPW 音频引擎尚未初始化");
            }
            return new BassLibrary(name -> {
                try (var symbolArena = Arena.ofConfined()) {
                    MemorySegment address = (MemorySegment) getExport.invokeExact(module, symbolArena.allocateFrom(name));
                    return address.address() == 0 ? Optional.empty() : Optional.of(address);
                } catch (Throwable error) {
                    throw new IllegalStateException("Could not resolve BASS export " + name, error);
                }
            });
        } catch (Throwable error) {
            throw new IllegalStateException("无法连接到 SPW 音频引擎", error);
        }
    }

    private BassLibrary(SymbolLookup exports) throws Throwable {
        var getVersion = bind(exports, "BASS_GetVersion", FunctionDescriptor.of(JAVA_INT));
        if ((int) getVersion.invokeExact() != BASS_2_4_18_3) {
            throw new IllegalStateException("Unsupported BASS build");
        }

        channelInfo = bind(exports, "BASS_ChannelGetInfo", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        channelLength = bind(exports, "BASS_ChannelGetLength", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT));
        channelPosition = bind(exports, "BASS_ChannelGetPosition", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT));
        addDsp = bind(exports, "BASS_ChannelSetDSPEx",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        freeDsp = bind(exports, "BASS_FXFree", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        openFile = bind(exports, "BASS_StreamCreateFile",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT));
        readData = bind(exports, "BASS_ChannelGetData", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        freeStream = bind(exports, "BASS_StreamFree", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    }

    AudioFormat format(int channel) throws Throwable {
        // BASS_CHANNELINFO on Windows x64 has seven DWORDs, padding, and a pointer.
        try (var arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(40, 8);
            if ((int) channelInfo.invokeExact(channel, info) == 0) {
                return null;
            }
            int rate = info.get(JAVA_INT, 0);
            int flags = info.get(JAVA_INT, 8);
            if ((flags & STREAM_DECODE) == 0 || (flags & SAMPLE_8BIT) != 0 || info.get(JAVA_INT, 4) != 2
                    || info.get(JAVA_INT, 12) == CTYPE_DSD || rate < 8000 || rate > 384000) {
                return null;
            }

            int frameBytes = 2 * ((flags & SAMPLE_FLOAT) != 0 ? Float.BYTES : Short.BYTES);
            long length = (long) channelLength.invokeExact(channel, 0);
            if (length <= 0 || length % frameBytes != 0 || length / frameBytes > Integer.MAX_VALUE) {
                return null;
            }
            return new AudioFormat(rate, (int) (length / frameBytes), frameBytes);
        }
    }

    AudioFormat decode(Path source, Path output, long cacheLimit, DoubleConsumer progress) throws IOException {
        try (var arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(source.toString(), StandardCharsets.UTF_16LE);
            int stream = (int) openFile.invokeExact(0, name, 0L, 0L,
                    UNICODE | STREAM_DECODE | SAMPLE_FLOAT | STREAM_PRESCAN);
            if (stream == 0) {
                throw new IOException("SPW 无法解码该音频文件");
            }
            try {
                AudioFormat format = format(stream);
                if (format == null || format.frameBytes() != AudioFormat.FLOAT_FRAME_BYTES) {
                    throw new IOException("当前只支持可定位的本地立体声 PCM 音频");
                }
                if ((long) format.frames() * StemCache.BYTES_PER_FRAME + 48 > cacheLimit) {
                    throw new IOException("缓存上限不足以保存这首歌");
                }
                writeDecoded(stream, format, output, arena, progress);
                return format;
            } finally {
                int result = (int) freeStream.invokeExact(stream);
                if (result == 0) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                            "BASS did not release the isolated decode stream");
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("音频解码失败", error);
        }
    }

    private void writeDecoded(int stream, AudioFormat format, Path output, Arena arena, DoubleConsumer progress)
            throws Throwable {
        long size = (long) format.frames() * format.frameBytes();
        MemorySegment block = arena.allocate(256 * 1024, Float.BYTES);
        try (var file = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long done = 0;
            int lastPercent = -1;
            while (done < size) {
                StemCache.checkInterrupted();
                int request = (int) Math.min(block.byteSize(), size - done);
                int bytes = (int) readData.invokeExact(stream, block, request);
                if (bytes <= 0 || bytes > request || bytes % format.frameBytes() != 0) {
                    throw new IOException("解码后的帧数与源音频不符");
                }
                var data = block.asSlice(0, bytes).asByteBuffer();
                while (data.hasRemaining()) {
                    file.write(data);
                }
                done += bytes;
                int percent = (int) (done * 100 / size);
                if (percent != lastPercent) {
                    progress.accept(done / (double) size);
                    lastPercent = percent;
                }
            }
        }
    }

    long dspBytePosition(int channel) throws Throwable {
        return (long) channelPosition.invokeExact(channel, POS_DSP);
    }

    int addDsp(int channel, MethodHandle callback, Arena arena) throws Throwable {
        MemorySegment function = LINKER.upcallStub(callback, DSP_PROC, arena);
        return (int) addDsp.invokeExact(channel, function, MemorySegment.NULL, 100, DSP_FREECALL | DSP_FLOAT);
    }

    boolean freeDsp(int dsp) throws Throwable {
        return (int) freeDsp.invokeExact(dsp) != 0;
    }

    private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor signature) {
        return LINKER.downcallHandle(lookup.findOrThrow(name), signature);
    }
}
