package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

record TrackMixOptions(int cacheGiB, boolean quality, boolean cpu, boolean fp16, MixSettings mix) {
    static final TrackMixOptions DEFAULT = new TrackMixOptions(10, false, false, false, MixSettings.DEFAULT);

    TrackMixOptions {
        if (cacheGiB < 1 || cacheGiB > 100) {
            throw new IllegalArgumentException("缓存上限应在 1 至 100 GiB 之间");
        }
    }

    long cacheBytes() {
        return (long) cacheGiB * 1024 * 1024 * 1024;
    }

    TrackMixOptions withMix(MixSettings value) {
        return new TrackMixOptions(cacheGiB, quality, cpu, fp16, value);
    }

    static TrackMixOptions read(Path path) {
        if (!Files.isRegularFile(path)) {
            return DEFAULT;
        }
        var values = new Properties();
        try (var stream = Files.newInputStream(path)) {
            values.load(stream);
            int[] levels = new int[MixSettings.STEMS];
            for (int index = 0; index < levels.length; index++) {
                levels[index] = Integer.parseInt(values.getProperty("track." + index, "100"));
            }
            var settings = new MixSettings(MixSettings.Preset.valueOf(values.getProperty("preset", "ORIGINAL")),
                    levels, Integer.parseInt(values.getProperty("mute", "0")),
                    Integer.parseInt(values.getProperty("solo", "0")));
            return new TrackMixOptions(Integer.parseInt(values.getProperty("cacheGiB", "10")),
                    value(values, "quality"), value(values, "cpu"), value(values, "fp16"), settings);
        } catch (IOException | IllegalArgumentException error) {
            System.getLogger(TrackMixOptions.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not load Track Mix settings", error);
            return DEFAULT;
        }
    }

    void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        var values = new Properties();
        values.setProperty("cacheGiB", Integer.toString(cacheGiB));
        values.setProperty("quality", Boolean.toString(quality));
        values.setProperty("cpu", Boolean.toString(cpu));
        values.setProperty("fp16", Boolean.toString(fp16));
        values.setProperty("preset", mix.preset.name());
        values.setProperty("mute", Integer.toString(mix.muted));
        values.setProperty("solo", Integer.toString(mix.solos));
        for (int index = 0; index < MixSettings.STEMS; index++) {
            values.setProperty("track." + index, Integer.toString(mix.volume(index)));
        }
        Path pending = path.resolveSibling("settings.pending");
        try (var stream = Files.newOutputStream(pending)) {
            values.store(stream, null);
        }
        Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean value(Properties properties, String key) {
        return Boolean.parseBoolean(properties.getProperty(key, "false"));
    }
}
