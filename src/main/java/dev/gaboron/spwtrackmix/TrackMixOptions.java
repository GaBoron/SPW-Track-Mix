package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

record TrackMixOptions(int cacheGiB, boolean quality, boolean cpu, boolean fp16) {
    static final TrackMixOptions DEFAULT = new TrackMixOptions(10, false, false, false);

    TrackMixOptions {
        if (cacheGiB < 1 || cacheGiB > 100) {
            throw new IllegalArgumentException("缓存上限应在 1 至 100 GiB 之间");
        }
    }

    long cacheBytes() {
        return (long) cacheGiB * 1024 * 1024 * 1024;
    }

    static TrackMixOptions read(Path path) {
        if (!Files.isRegularFile(path)) {
            return DEFAULT;
        }
        var values = new Properties();
        try (var stream = Files.newInputStream(path)) {
            values.load(stream);
            return new TrackMixOptions(Integer.parseInt(values.getProperty("cacheGiB", "10")),
                    value(values, "quality"), value(values, "cpu"), value(values, "fp16"));
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
