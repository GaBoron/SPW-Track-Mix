package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

/** Prepares complete files in the new directory before the session changes its active cache. */
record CacheDirectoryMigration(List<Path> transferred, int conflicts) {
    private static final System.Logger LOG = System.getLogger(CacheDirectoryMigration.class.getName());

    static CacheDirectoryMigration prepare(Path source, Path target, PythonEngine.Task task) throws IOException {
        if (!Files.isDirectory(source)) return new CacheDirectoryMigration(List.of(), 0);
        source = source.toRealPath();
        target = target.toRealPath();
        if (source.equals(target)) return new CacheDirectoryMigration(List.of(), 0);

        var files = StemCache.filesIn(source);
        var targetFiles = new HashMap<String, List<Path>>();
        for (Path file : StemCache.filesIn(target)) {
            targetFiles.computeIfAbsent(CacheFileName.parse(file).key(), ignored -> new ArrayList<>()).add(file);
        }
        var transferred = new ArrayList<Path>();
        int conflicts = 0;
        boolean sameVolume = Files.getFileStore(source).equals(Files.getFileStore(target));
        long totalBytes = 0;
        for (Path file : files) totalBytes += Files.size(file);
        long completed = 0;
        for (Path file : files) {
            task.check();
            String key = CacheFileName.parse(file).key();
            var copies = targetFiles.computeIfAbsent(key, ignored -> new ArrayList<>());
            Path destination = target.resolve(file.getFileName().toString());
            if (copies.isEmpty() && transfer(file, destination, sameVolume, task)) {
                copies.add(destination);
                transferred.add(file);
            } else if (includesSameContent(file, copies)) {
                transferred.add(file);
            } else {
                conflicts++;
            }
            completed += Files.size(file);
            task.report("cache-directory", totalBytes == 0 ? 0 : completed / (double) totalBytes);
        }
        task.check();
        var existingTitles = StemCache.readTitles(target);
        var mergedTitles = new Properties();
        mergedTitles.putAll(StemCache.readTitles(source));
        mergedTitles.putAll(existingTitles);
        if (!mergedTitles.equals(existingTitles)) StemCache.writeTitles(target, mergedTitles);
        return new CacheDirectoryMigration(List.copyOf(transferred), conflicts);
    }

    private static boolean includesSameContent(Path source, List<Path> copies) throws IOException {
        for (Path copy : copies) {
            if (sameContent(source, copy)) return true;
        }
        return false;
    }

    private static boolean transfer(Path source, Path destination, boolean sameVolume, PythonEngine.Task task)
            throws IOException {
        try {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return sameContent(source, destination);
            if (sameVolume) {
                try {
                    Files.createLink(destination, source);
                    return true;
                } catch (FileAlreadyExistsException concurrent) {
                    return sameContent(source, destination);
                } catch (IOException | UnsupportedOperationException unavailable) {
                    // Filesystems without hard links use the same copy path as a different volume.
                }
            }
            copyComplete(source, destination, task);
            return true;
        } catch (FileAlreadyExistsException concurrent) {
            return sameContent(source, destination);
        }
    }

    private static boolean sameContent(Path source, Path destination) throws IOException {
        boolean sameFilesystem = source.getFileSystem().equals(destination.getFileSystem());
        return Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                && ((sameFilesystem && Files.isSameFile(source, destination)) || Files.mismatch(source, destination) == -1);
    }

    private static void copyComplete(Path source, Path destination, PythonEngine.Task task) throws IOException {
        Path pending = Files.createTempFile(destination.getParent(), ".cache-move-", ".pending");
        try {
            try (var input = Files.newInputStream(source); var output = Files.newOutputStream(pending)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    task.check();
                    output.write(buffer, 0, count);
                }
            }
            Files.setLastModifiedTime(pending, Files.getLastModifiedTime(source));
            task.check();
            // Both paths are in the same directory. The move cannot overwrite a conflicting cache.
            Files.move(pending, destination);
        } finally {
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    WindowsRecycle.recycle(pending);
                } catch (IOException error) {
                    LOG.log(System.Logger.Level.WARNING, "Could not recycle incomplete cache copy " + pending, error);
                }
            }
        }
    }

    int recycleOriginals() {
        int retained = conflicts;
        for (Path source : transferred) {
            try {
                WindowsRecycle.recycle(source);
            } catch (IOException error) {
                retained++;
                LOG.log(System.Logger.Level.WARNING, "Could not recycle migrated cache " + source, error);
            }
        }
        return retained;
    }
}
