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
                    new float[]{0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f}, 0, null);
            var window = new StemCache.Window(entry, 0, samples);
            var buffer = arena.allocate(500 * AudioFormat.FLOAT_FRAME_BYTES);
            var mixer = new RealtimeStemMixer(44100);
            var original = MixSettings.DEFAULT;

            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, entry, window, original);
            near(buffer.getAtIndex(FLOAT, 995), SOURCE, "original is exact");

            var vocals = original.withPreset(MixSettings.Preset.VOCALS);
            mixer.process(buffer, 500, 500, entry, window, vocals);
            near(buffer.getAtIndex(FLOAT, 0), SOURCE + (0.05f - SOURCE) / 441, "vocal fade starts");
            near(buffer.getAtIndex(FLOAT, 998), 0.05f, "vocals only");
            near(buffer.getAtIndex(FLOAT, 999), -0.05f, "stereo preserved");

            var custom = original.withVolume(1, 125).withSolo(0).withSolo(1).withMuted(0);
            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, entry, window, custom);
            near(buffer.getAtIndex(FLOAT, 998), 0.125f, "mute overrides multiple solos and gain adjusts");
            var accompaniment = original.withPreset(MixSettings.Preset.INSTRUMENTAL);
            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, entry, window, accompaniment);
            near(buffer.getAtIndex(FLOAT, 998), 0.995f, "fixed headroom prevents clipping");

            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, entry, null, custom);
            require(buffer.getAtIndex(FLOAT, 998) == 0, "pending seek leaked original PCM");
            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, entry, window, custom);
            require(Math.abs(buffer.getAtIndex(FLOAT, 0)) < 0.001f, "seek fade did not start at zero");
            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, entry, window, original.withPreset(MixSettings.Preset.CUSTOM));
            require(buffer.getAtIndex(FLOAT, 998) == SOURCE, "default custom mix must be untouched");
        }
        loudnessChecks();
        spectrumChecks();
        cacheChecks();
        System.out.println("Mixer, loudness, cached PCM and source-positioned FFT verified.");
    }

    private static void loudnessChecks() throws IOException {
        int rate = 48000;
        var stems = ByteBuffer.allocate(StemCache.BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
        var reference = ByteBuffer.allocate(AudioFormat.FLOAT_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var analysis = new LoudnessProfile.Analysis(rate);
        for (int frame = 0; frame < rate; frame++) {
            float sample = (float) Math.sin(2 * Math.PI * 1000 * frame / rate);
            stems.putFloat(0, sample * .1f).putFloat(4, sample * .1f);
            stems.putFloat(8, sample * .2f).putFloat(12, sample * .2f);
            reference.putFloat(0, sample * .4f).putFloat(4, sample * .4f);
            analysis.accept(stems, 0, reference, 0);
        }
        LoudnessProfile profile = analysis.result();
        near(profile.gainFor(MixSettings.DEFAULT.withPreset(MixSettings.Preset.VOCALS)), 4f, "solo loudness");
        near(profile.gainFor(MixSettings.DEFAULT.withSolo(0).withSolo(1)), 4f / 3, "combined loudness");
        near(profile.gainFor(MixSettings.DEFAULT.withSolo(0).withVolume(0, 50)), 4f, "manual fader remains relative");
    }

    private static void spectrumChecks() {
        int rate = 48000;
        int frames = 8192;
        var tap = new MixedSpectrum(rate);
        try (var arena = Arena.ofConfined()) {
            var pcm = arena.allocate((long) frames * AudioFormat.FLOAT_FRAME_BYTES);
            for (int frame = 0; frame < frames; frame++) {
                float signal = .4f * (float) Math.sin(2 * Math.PI * 128 * frame / frames);
                pcm.setAtIndex(FLOAT, 2L * frame, signal);
                pcm.setAtIndex(FLOAT, 2L * frame + 1, signal);
            }
            tap.write(pcm, 0, frames);
            Object[] frame = tap.at(85);
            var bass = (float[]) frame[1];
            var detail = (float[]) frame[2];
            require((int) frame[0] == rate && bass.length == 4096 && detail.length == 1024,
                    "spectrum format differs from AMLL's bands");
            near(bass[128], .4f, "mixed FFT amplitude");
            near(detail[32], .4f, "mixed detail FFT amplitude");
            require(bass[256] < .0001f, "spectrum contains a tone absent from the selected mix");
            fill(pcm, 0);
            tap.write(pcm, rate, frames);
            require(((float[]) tap.at(85)[1]).length == 0, "seek exposed old spectrum");
            require(((float[]) tap.at(1085)[1])[128] == 0f, "silent mix must produce a silent spectrum");
        }
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
            var entry = cache.publish(identity, 48000, FRAMES, raw, null, directory, Long.MAX_VALUE);
            require(cache.lookup(identity).crc() == entry.crc(), "cached data not reused");
            var window = StemCache.readWindow(entry, 102, 100);
            require(window.covers(102, 100) && !window.covers(101, 100), "window seek bounds");
            require(window.samples()[1199] == 0.125f && entry.peak(5) == 0.125f, "PCM lost precision");
            var bytes = Files.readAllBytes(entry.path());
            int baseBytes = entry.headerBytes() - LoudnessProfile.BYTES;
            var legacy = ByteBuffer.allocate(baseBytes + data.capacity()).order(ByteOrder.LITTLE_ENDIAN);
            legacy.put(bytes, 0, baseBytes).put(bytes, entry.headerBytes(), data.capacity()).putInt(4, 1);
            Files.write(entry.path(), legacy.array());
            var oldCache = cache.lookup(identity);
            require(oldCache.headerBytes() == baseBytes, "legacy cache header not accepted");
            near(oldCache.loudness().gainFor(MixSettings.DEFAULT.withPreset(MixSettings.Preset.VOCALS)), 6,
                    "legacy loudness estimated from all stems");
            near(StemCache.readWindow(oldCache, 102, 100).samples()[1199], .125f, "legacy PCM offset");
            Files.write(entry.path(), bytes);
            long size = cache.size();
            var second = cache.publish(StemCache.Identity.read(other), 48000, FRAMES, raw, null, directory, size);
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
