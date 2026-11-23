package dev.gaboron.spwtrackmix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class BassLibrary {
    private static final int BASS_2_4_18_3 = 0x02041203;
    private static final int POS_DSP = 0x800000;
    private static final int SAMPLE_8BIT = 0x1;
    private static final int SAMPLE_FLOAT = 0x100;
    private static final int STREAM_DECODE = 0x200000;
    private static final int WAV_PCM = 0x50001;
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

    static BassLibrary resolveLoaded(Path dll) {
        // Look up exports in the existing module. Calling into a second BASS instance
        // would give the host's channel handles to the wrong engine.
        var kernel = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        var getModule = bind(kernel, "GetModuleHandleW", FunctionDescriptor.of(ADDRESS, ADDRESS));
        var getExport = bind(kernel, "GetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try (var arena = Arena.ofConfined()) {
            MemorySegment module = (MemorySegment) getModule.invokeExact(
                    arena.allocateFrom(dll.toString(), StandardCharsets.UTF_16LE));
            if (module.address() == 0) {
                throw new IllegalStateException("The SPW BASS module is not loaded");
            }
            return new BassLibrary(name -> {
                try (var symbolArena = Arena.ofConfined()) {
                    MemorySegment address = (MemorySegment) getExport.invokeExact(module, symbolArena.allocateFrom(name));
                    return address.address() == 0 ? java.util.Optional.empty() : java.util.Optional.of(address);
                } catch (Throwable error) {
                    throw new IllegalStateException("Could not resolve BASS export " + name, error);
                }
            });
        } catch (Throwable error) {
            throw new IllegalStateException("Could not open the SPW BASS module", error);
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
    }

    int probeFrameBytes(int channel) throws Throwable {
        // BASS_CHANNELINFO on Windows x64 has seven DWORDs, padding, and a pointer.
        try (var arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(40, 8);
            if ((int) channelInfo.invokeExact(channel, info) == 0) {
                return 0;
            }
            int flags = info.get(JAVA_INT, 8);
            if ((flags & STREAM_DECODE) == 0 || (flags & SAMPLE_8BIT) != 0
                    || info.get(JAVA_INT, 0) != ProbeSignal.SAMPLE_RATE
                    || info.get(JAVA_INT, 4) != ProbeSignal.CHANNELS
                    || info.get(JAVA_INT, 12) != WAV_PCM
                    || info.get(JAVA_INT, 16) != 16) {
                return 0;
            }

            int sampleBytes = (flags & SAMPLE_FLOAT) != 0 ? Float.BYTES : Short.BYTES;
            int frameBytes = ProbeSignal.CHANNELS * sampleBytes;
            long length = (long) channelLength.invokeExact(channel, 0);
            return length == (long) ProbeSignal.FRAME_COUNT * frameBytes ? frameBytes : 0;
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
