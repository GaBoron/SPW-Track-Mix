package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class PcmChecks {
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final float SOURCE = 0.25f;
    private static final int FRAMES = 1700;

    public static void main(String[] args) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var samples = new float[FRAMES * StemCache.FLOATS_PER_FRAME];
            for (int frame = 0; frame < FRAMES; frame++) {
                for (int lane = 0; lane < MixSettings.STEMS; lane++) {
                    int start = frame * StemCache.FLOATS_PER_FRAME + lane * 2;
                    samples[start] = 0.05f * (lane + 1);
                    samples[start + 1] = -samples[start];
                }
            }
            var entry = new StemCache.Entry(Path.of("fixture"), 44100, FRAMES, 0,
                    new float[]{0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f});
            var window = new StemCache.Window(entry, 0, samples);
            var buffer = arena.allocate(500 * AudioFormat.FLOAT_FRAME_BYTES);
            var mixer = new RealtimeStemMixer(44100);
            var original = MixSettings.DEFAULT;

            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, window, original);
            near(buffer.getAtIndex(FLOAT, 995), SOURCE, "original is exact");

            var vocals = original.withPreset(MixSettings.Preset.VOCALS);
            mixer.process(buffer, 500, 500, window, vocals);
            near(buffer.getAtIndex(FLOAT, 0), SOURCE + (0.05f - SOURCE) / 441, "vocal fade starts");
            near(buffer.getAtIndex(FLOAT, 998), 0.05f, "vocals only");
            near(buffer.getAtIndex(FLOAT, 999), -0.05f, "stereo preserved");

            var custom = original.withVolume(1, 125).withSolo(0).withSolo(1).withMuted(0);
            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, window, custom);
            near(buffer.getAtIndex(FLOAT, 998), 0.125f, "mute overrides multiple solos and gain adjusts");
            var accompaniment = original.withPreset(MixSettings.Preset.INSTRUMENTAL);
            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, window, accompaniment);
            near(buffer.getAtIndex(FLOAT, 998), 0.995f, "fixed headroom prevents clipping");

            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, null, custom);
            near(buffer.getAtIndex(FLOAT, 998), SOURCE, "missing window is dry");
            mixer.process(buffer, 0, 500, window, original.withPreset(MixSettings.Preset.CUSTOM));
            require(buffer.getAtIndex(FLOAT, 998) == SOURCE, "default custom mix must be untouched");
        }
        cacheChecks();
        System.out.println("Mixer and cache verified: presets, multiple solos, ramps, headroom, original bypass and corruption.");
    }

    private static void cacheChecks() throws IOException {
        Path directory = Files.createTempDirectory("spw-track-mix-cache-check-").toAbsolutePath().normalize();
        try {
            Path source = Files.writeString(directory.resolve("track"), "first source");
            Path other = Files.writeString(directory.resolve("other"), "second source");
            Path raw = directory.resolve("six.f32");
            var data = ByteBuffer.allocate(FRAMES * StemCache.BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
            while (data.hasRemaining()) {
                data.putFloat(0.125f);
            }
            Files.write(raw, data.array());
            Path cacheDir = directory.resolve("cache");
            Path retired = Files.createDirectory(directory.resolve("retired"));
            var cache = new StemCache(cacheDir, path -> Files.move(path, retired.resolve(path.getFileName())));
            var identity = StemCache.Identity.read(source);
            var entry = cache.publish(identity, 48000, FRAMES, raw, directory, Long.MAX_VALUE);
            require(cache.lookup(identity).crc() == entry.crc(), "cached data not reused");
            var window = StemCache.readWindow(entry, 102, 100);
            require(window.covers(102, 100) && !window.covers(101, 100), "window seek bounds");
            require(window.samples()[1199] == 0.125f && entry.peak(5) == 0.125f, "PCM lost precision");
            long size = cache.size();
            var second = cache.publish(StemCache.Identity.read(other), 48000, FRAMES, raw, directory, size);
            require(cache.size() == size && cache.lookup(identity) == null, "old cache not evicted");
            try (var file = FileChannel.open(second.path(), StandardOpenOption.WRITE)) {
                file.position(file.size() - 1);
                file.write(ByteBuffer.wrap(new byte[]{-1}));
            }
            try {
                cache.lookup(StemCache.Identity.read(other));
                throw new AssertionError("corruption accepted");
            } catch (IOException expected) {
                require(expected.getMessage() != null, "corruption reason missing");
            }
            Files.writeString(source, "changed source");
            require(!identity.current(), "source rewrite not recognized");
            require(!identity.digest().equals(StemCache.Identity.read(source).digest()), "source checksum did not change");
        } finally {
            WindowsRecycle.recycle(directory);
        }
    }

    private static void fill(MemorySegment memory, float value) {
        for (long index = 0; index < memory.byteSize() / Float.BYTES; index++) {
            memory.setAtIndex(FLOAT, index, value);
        }
    }

    static void near(float actual, float expected, String label) {
        require(Math.abs(actual - expected) <= 3.1e-5f, label + ": expected " + expected + ", actual " + actual);
    }

    static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
