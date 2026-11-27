package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

record TrackMixOptions(int cacheGiB, boolean quality, boolean cpu, boolean fp16, Path cacheParent) {
    static final String CACHE_FOLDER = "SPW Track Mix Cache";
    static final TrackMixOptions DEFAULT = new TrackMixOptions(10, false, false, false);

    TrackMixOptions(int cacheGiB, boolean quality, boolean cpu, boolean fp16) {
        this(cacheGiB, quality, cpu, fp16, null);
    }

    TrackMixOptions {
        if (cacheGiB < 1 || cacheGiB > 100) {
            throw new IllegalArgumentException("缓存上限应在 1 至 100 GiB 之间");
        }
        if (cacheParent != null) {
            if (!cacheParent.isAbsolute()) throw new IllegalArgumentException("缓存目录必须是绝对路径");
            cacheParent = cacheParent.normalize();
        }
    }

    long cacheBytes() {
        return (long) cacheGiB * 1024 * 1024 * 1024;
    }

    Path cacheDirectory(Path defaultDirectory) {
        return cacheParent == null ? defaultDirectory : cacheParent.resolve(CACHE_FOLDER);
    }

    TrackMixOptions withCacheParent(Path folder) {
        return new TrackMixOptions(cacheGiB, quality, cpu, fp16, folder);
    }

    static TrackMixOptions read(Path path) {
        if (!Files.isRegularFile(path)) {
            return DEFAULT;
        }
        var values = new Properties();
        try (var stream = Files.newInputStream(path)) {
            values.load(stream);
            String folder = values.getProperty("cacheParent", "");
            return new TrackMixOptions(Integer.parseInt(values.getProperty("cacheGiB", "10")),
                    value(values, "quality"), value(values, "cpu"), value(values, "fp16"),
                    folder.isBlank() ? null : Path.of(folder));
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
        if (cacheParent != null) values.setProperty("cacheParent", cacheParent.toString());
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
