package dev.gaboron.spwtrackmix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static dev.gaboron.spwtrackmix.PcmChecks.near;
import static dev.gaboron.spwtrackmix.PcmChecks.require;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class BassChecks {
    private static final int DECODE = 0x200000;
    private static final int FLOAT = 0x100;
    private static final int UNICODE = 0x80000000;
    private static final int LOOP = 4;
    private static final int NO_RAMP = 0x800000;
    private static final int PAUSED = 0x20000;
    private static final int FRAMES = 711;
    private static final Linker LINKER = Linker.nativeLinker();

    public static void main(String[] args) throws Throwable {
        Path resources = Path.of(args[0]).toAbsolutePath();
        Path fixture = Path.of(args[1]).toAbsolutePath();
        Path workspace = Files.createTempDirectory("spw-track-mix-bass-check-").toAbsolutePath().normalize();
        try (var arena = Arena.ofConfined()) {
            var bassPath = resources.resolve("bass.dll");
            var bass = SymbolLookup.libraryLookup(bassPath, arena);
            var mixer = SymbolLookup.libraryLookup(resources.resolve("bassmix.dll"), arena);
            var init = bind(bass, "BASS_Init", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
            require((int) init.invokeExact(0, 44100, 0, MemorySegment.NULL, MemorySegment.NULL) != 0,
                    "BASS could not initialize its no-sound device");
            try {
                var library = BassLibrary.resolveLoaded(bassPath);
                Path pcm = workspace.resolve("decoded.f32");
                AudioFormat decoded = library.decode(fixture, pcm, Long.MAX_VALUE, progress -> {});
                require(decoded.rate() == 44100 && decoded.frames() == ProbeSignal.FRAME_COUNT
                        && Files.size(pcm) == ProbeSignal.FRAME_COUNT * 8L, "isolated decode lost frames");
                var entry = fixtureCache(fixture, workspace);
                exercise(bass, mixer, library, fixture, entry, arena, FLOAT, 8);
                exercise(bass, mixer, library, fixture, entry, arena, 0, 4);
            } finally {
                var free = bind(bass, "BASS_Free", JAVA_INT);
                require((int) free.invokeExact() != 0, "BASS could not release its no-sound device");
            }
        } finally {
            WindowsRecycle.recycle(workspace);
        }
        System.out.println("Native BASS verified: decode, float and PCM16 DSP, pause, seek recovery, loop, source change and detach.");
    }

    private static StemCache.Entry fixtureCache(Path fixture, Path work) throws Exception {
        Path raw = work.resolve("six.f32");
        int count = ProbeSignal.FRAME_COUNT;
        var samples = ByteBuffer.allocate(count * StemCache.BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < count; frame++) {
            for (int lane = 0; lane < MixSettings.STEMS; lane++) {
                for (int channel = 0; channel < 2; channel++) {
                    samples.putFloat(ProbeSignal.stemSample(lane, frame, channel) / 32768f);
                }
            }
        }
        Files.write(raw, samples.array());
        return new StemCache(work.resolve("cache"), WindowsRecycle::recycle)
                .publish(StemCache.Identity.read(fixture), 44100, count, raw, work.resolve("decoded.f32"), work, Long.MAX_VALUE);
    }

    private static void exercise(SymbolLookup bass, SymbolLookup mixer, BassLibrary library, Path fixture,
                                 StemCache.Entry cache, Arena arena, int format, int sourceFrameBytes) throws Throwable {
        var create = bind(bass, "BASS_StreamCreateFile", JAVA_INT, JAVA_INT, ADDRESS,
                JAVA_LONG, JAVA_LONG, JAVA_INT);
        var createMixer = bind(mixer, "BASS_Mixer_StreamCreate", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        var add = bind(mixer, "BASS_Mixer_StreamAddChannel", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        var sourceFlags = bind(mixer, "BASS_Mixer_ChannelFlags", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        var seek = bind(mixer, "BASS_Mixer_ChannelSetPosition", JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
        var read = bind(bass, "BASS_ChannelGetData", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
        var free = bind(bass, "BASS_StreamFree", JAVA_INT, JAVA_INT);
        MemorySegment name = arena.allocateFrom(fixture.toString(), StandardCharsets.UTF_16LE);
        int flags = UNICODE | DECODE | LOOP | format;
        int stream = (int) create.invokeExact(0, name, 0L, 0L, flags);
        int mix = (int) createMixer.invokeExact(44100, 2, DECODE | FLOAT);
        require(stream != 0 && mix != 0, "BASS could not open the test source and mixer");
        var buffer = arena.allocate(FRAMES * 8, 8);
        var settings = new AtomicReference<>(MixSettings.DEFAULT);
        var admitted = new AtomicReference<>(cache);
        try (var effects = new BassSourceDsp(library, settings::get, admitted::get)) {
            require(!effects.attach(0, fixture), "invalid channel accepted");
            require(effects.attach(stream, fixture), "could not attach DSP");
            require(!effects.attach(stream, fixture), "duplicate DSP");
            effects.prefetch(fixture, cache);
            require((int) add.invokeExact(mix, stream, NO_RAMP) != 0, "could not add source");
            pull(read, mix, buffer);
            check(buffer, 0, 0, -1);
            require((int) sourceFlags.invokeExact(stream, PAUSED, PAUSED) != -1, "could not pause source");
            buffer.fill((byte) 0);
            int paused = (int) read.invokeExact(mix, buffer, (int) buffer.byteSize());
            require(paused == 0 || (paused == buffer.byteSize() && buffer.get(JAVA_FLOAT, 0) == 0),
                    "paused mixer emitted source audio");
            require((int) sourceFlags.invokeExact(stream, 0, PAUSED) != -1, "could not resume source");

            settings.set(settings.get().withPreset(MixSettings.Preset.VOCALS));
            pull(read, mix, buffer);
            float gain = cache.loudness().gainFor(settings.get());
            check(buffer, FRAMES, 500, 0, gain);
            int jump = 132407;
            seek(seek, stream, jump, sourceFrameBytes);
            pull(read, mix, buffer);
            for (int sample = 0; sample < FRAMES * 2; sample++) {
                require(buffer.getAtIndex(JAVA_FLOAT, sample) == 0, "unprepared seek leaked original audio");
            }
            effects.prefetch(fixture, cache);
            pull(read, mix, buffer);
            check(buffer, jump + FRAMES, 500, 0, gain);
            int prepared = 260_000;
            require(effects.prepareSeek(stream, (long) prepared * sourceFrameBytes), "seek was not scheduled");
            effects.prefetch(fixture, cache);
            seek(seek, stream, prepared, sourceFrameBytes);
            pull(read, mix, buffer);
            require(Math.abs(buffer.getAtIndex(JAVA_FLOAT, 0)) < .002f, "prepared seek used dry audio");
            check(buffer, prepared, 500, 0, gain);
            Object[] mixedSpectrum = effects.spectrum(fixture, (prepared + 500L) * 1000 / cache.rate());
            require(mixedSpectrum != null && ((float[]) mixedSpectrum[1]).length == 4096,
                    "BASS mixed PCM did not reach AMLL's spectrum");
            require(((float[]) effects.spectrum(fixture, (jump + 500L) * 1000 / cache.rate())[1]).length == 0,
                    "BASS seek left the prior spectrum available");

            admitted.set(null);
            pull(read, mix, buffer);
            check(buffer, prepared + FRAMES, 0, -1);
            int beforeLoop = ProbeSignal.FRAME_COUNT - 110;
            seek(seek, stream, beforeLoop, sourceFrameBytes);
            pull(read, mix, buffer);
            check(buffer, beforeLoop, 0, -1);

            admitted.set(cache);
            effects.close();
            seek(seek, stream, 1200, sourceFrameBytes);
            pull(read, mix, buffer);
            check(buffer, 1200, 0, -1);
        }
        try (var effects = new BassSourceDsp(library, settings::get, admitted::get)) {
            require(effects.attach(stream, fixture), "replacement adapter could not attach");
            require((int) free.invokeExact(stream) != 0, "fixture stream could not be freed");
            int replacement = (int) create.invokeExact(0, name, 0L, 0L, flags);
            require(effects.attach(replacement, fixture), "replacement source did not attach");
            effects.prefetch(fixture, cache);
            require((int) add.invokeExact(mix, replacement, NO_RAMP) != 0, "could not add replacement source");
            pull(read, mix, buffer);
            check(buffer, 0, 500, 0, cache.loudness().gainFor(settings.get()));
            require((int) free.invokeExact(replacement) != 0, "replacement stream could not be freed");
        }
        require((int) free.invokeExact(mix) != 0, "test mixer could not be freed");
    }

    private static void check(MemorySegment block, int sourceFrame, int skip, int track) {
        check(block, sourceFrame, skip, track, 1);
    }

    private static void check(MemorySegment block, int sourceFrame, int skip, int track, float gain) {
        for (int frame = skip; frame < FRAMES; frame++) {
            for (int channel = 0; channel < 2; channel++) {
                short value = track < 0 ? ProbeSignal.originalSample(sourceFrame + frame, channel)
                        : ProbeSignal.stemSample(track, sourceFrame + frame, channel);
                near(block.getAtIndex(JAVA_FLOAT, frame * 2L + channel), value / 32768f * gain,
                        "BASS source frame " + (sourceFrame + frame));
            }
        }
    }

    private static void seek(MethodHandle seek, int stream, int frame, int bytes) throws Throwable {
        require((int) seek.invokeExact(stream, (long) frame * bytes, NO_RAMP) != 0, "source seek failed");
    }

    private static void pull(MethodHandle read, int mixer, MemorySegment buffer) throws Throwable {
        require((int) read.invokeExact(mixer, buffer, (int) buffer.byteSize()) == buffer.byteSize(),
                "mixer returned an incomplete PCM block");
    }

    private static MethodHandle bind(SymbolLookup library, String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(library.findOrThrow(name), FunctionDescriptor.of(result, args));
    }
}
