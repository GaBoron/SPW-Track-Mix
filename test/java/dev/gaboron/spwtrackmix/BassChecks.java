package dev.gaboron.spwtrackmix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static dev.gaboron.spwtrackmix.PcmChecks.require;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class BassChecks {
    private static final int DECODE = 0x200000;
    private static final int FLOAT = 0x100;
    private static final int UNICODE = 0x80000000;
    private static final int LOOP = 4;
    private static final int NO_RAMP = 0x800000;
    private static final int PULL_FRAMES = 711;
    private static final Linker LINKER = Linker.nativeLinker();

    public static void main(String[] args) throws Throwable {
        Path resources = Path.of(args[0]).toAbsolutePath();
        Path fixture = Path.of(args[1]).toAbsolutePath();
        try (var arena = Arena.ofConfined()) {
            var bassPath = resources.resolve("bass.dll");
            var bass = SymbolLookup.libraryLookup(bassPath, arena);
            var mixer = SymbolLookup.libraryLookup(resources.resolve("bassmix.dll"), arena);
            var init = bind(bass, "BASS_Init", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
            require((int) init.invokeExact(0, 44100, 0, MemorySegment.NULL, MemorySegment.NULL) != 0,
                    "BASS could not initialize its no-sound device");
            try {
                var library = BassLibrary.resolveLoaded(bassPath);
                exercise(bass, mixer, library, fixture, arena, FLOAT, 8);
                exercise(bass, mixer, library, fixture, arena, 0, 4);
            } finally {
                var free = bind(bass, "BASS_Free", JAVA_INT);
                require((int) free.invokeExact() != 0, "BASS could not release its no-sound device");
            }
        }
        System.out.println("Native BASS probe verified: float and PCM16, seeks, source lifecycle and bypass.");
    }

    private static void exercise(SymbolLookup bass, SymbolLookup mixer, BassLibrary library,
                                 Path fixture, Arena arena, int format, int sourceFrameBytes) throws Throwable {
        var create = bind(bass, "BASS_StreamCreateFile", JAVA_INT, JAVA_INT, ADDRESS,
                JAVA_LONG, JAVA_LONG, JAVA_INT);
        var createMixer = bind(mixer, "BASS_Mixer_StreamCreate", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        var add = bind(mixer, "BASS_Mixer_StreamAddChannel", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        var seek = bind(mixer, "BASS_Mixer_ChannelSetPosition", JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
        var read = bind(bass, "BASS_ChannelGetData", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
        var free = bind(bass, "BASS_StreamFree", JAVA_INT, JAVA_INT);
        MemorySegment name = arena.allocateFrom(fixture.toString(), StandardCharsets.UTF_16LE);
        int flags = UNICODE | DECODE | LOOP | format;
        int stream = (int) create.invokeExact(0, name, 0L, 0L, flags);
        int mix = (int) createMixer.invokeExact(44100, 2, DECODE | FLOAT);
        require(stream != 0 && mix != 0, "BASS could not open the test source and mixer");

        var buffer = arena.allocate(PULL_FRAMES * 8, 8);
        try (var effects = new BassSourceDsp(library)) {
            require(!effects.attach(0), "invalid channel accepted");
            require(effects.attach(stream), "could not attach to the decoded test WAV");
            require(!effects.attach(stream), "duplicate DSP attached to one channel");
            require((int) add.invokeExact(mix, stream, NO_RAMP) != 0, "could not add the source to BASSmix");
            pull(read, mix, buffer);
            PcmChecks.checkOriginal(buffer, 0, PULL_FRAMES);
            for (int start : new int[]{132407, 44831, 88001, 309116}) {
                seek(seek, stream, start, sourceFrameBytes);
                pull(read, mix, buffer);
                // PCM16 rounds the crossfade back to native precision. Both fixtures share the exact
                // source clock, and the float path checks the interpolated values at the transitions.
                if (format == FLOAT || (start % 44100 >= 441 && start % 44100 + PULL_FRAMES < 44100)) {
                    PcmChecks.checkOutput(buffer, start, PULL_FRAMES);
                }
            }

            effects.close();
            int sample = 44213;
            seek(seek, stream, sample, sourceFrameBytes);
            pull(read, mix, buffer);
            PcmChecks.checkOriginal(buffer, sample, PULL_FRAMES);
            require(!effects.attach(stream), "closed adapter accepted a source");
        }

        try (var effects = new BassSourceDsp(library)) {
            require(effects.attach(stream), "replacement adapter could not attach");
            require((int) free.invokeExact(stream) != 0, "fixture stream could not be freed");
            int replacement = (int) create.invokeExact(0, name, 0L, 0L, flags);
            require(replacement != 0 && effects.attach(replacement), "replacement source could not attach");
            require((int) add.invokeExact(mix, replacement, NO_RAMP) != 0, "could not add replacement source");
            int start = 177100;
            seek(seek, replacement, start, sourceFrameBytes);
            pull(read, mix, buffer);
            PcmChecks.checkOutput(buffer, start, PULL_FRAMES);
            require((int) free.invokeExact(replacement) != 0, "replacement stream could not be freed");
        }
        require((int) free.invokeExact(mix) != 0, "test mixer could not be freed");
    }

    private static void seek(MethodHandle seek, int stream, int frame, int frameBytes) throws Throwable {
        require((int) seek.invokeExact(stream, (long) frame * frameBytes, NO_RAMP) != 0, "source seek failed");
    }

    private static void pull(MethodHandle read, int mixer, MemorySegment buffer) throws Throwable {
        require((int) read.invokeExact(mixer, buffer, (int) buffer.byteSize()) == buffer.byteSize(),
                "mixer returned an incomplete PCM block");
    }

    private static MethodHandle bind(SymbolLookup library, String name, java.lang.foreign.MemoryLayout result,
                                     java.lang.foreign.MemoryLayout... args) {
        return LINKER.downcallHandle(library.findOrThrow(name), FunctionDescriptor.of(result, args));
    }
}
