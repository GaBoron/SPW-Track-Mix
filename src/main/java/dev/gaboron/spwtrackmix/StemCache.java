package dev.gaboron.spwtrackmix;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;

final class StemCache implements AutoCloseable {
    static final int FLOATS_PER_FRAME = MixSettings.STEMS * 2;
    static final int BYTES_PER_FRAME = FLOATS_PER_FRAME * Float.BYTES;
    static final int MAX_WINDOW_FRAMES = 192_000;
    private static final int MAGIC = 0x58494d54;
    private static final int JEMA_METADATA_VERSION = 1;
    private static final int JEMA_METADATA_BYTES = 2 * Integer.BYTES + MixSettings.STEMS * Float.BYTES
            + LoudnessProfile.BYTES;
    private static final int CHUNK_BYTES = BYTES_PER_FRAME * 16384;
    private static final System.Logger LOG = System.getLogger(StemCache.class.getName());
    private volatile Path root;
    private final Recycler recycler;
    private final JemaCodec jema;

    @FunctionalInterface
    interface Recycler {
        void recycle(Path path) throws IOException;
    }

    StemCache(Path root, Recycler recycler) {
        this(root, root.toAbsolutePath().normalize().resolveSibling("codec"), recycler);
    }

    StemCache(Path root, Path codecDirectory, Recycler recycler) {
        this.root = root.toAbsolutePath().normalize();
        this.recycler = recycler;
        this.jema = new JemaCodec(codecDirectory);
    }

    Path root() {
        return root;
    }

    synchronized void changeRoot(Path directory) {
        root = directory.toAbsolutePath().normalize();
    }

    record Identity(Path path, Object fileKey, long length, FileTime modified, String digest) {
        static Identity read(Path source) throws IOException {
            Path path = source.toAbsolutePath().normalize();
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
            if (!before.isRegularFile() || before.size() == 0) {
                throw new IOException("本地音频文件不可读");
            }
            var digest = sha256();
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[256 * 1024];
                for (int size; (size = input.read(buffer)) >= 0;) {
                    checkInterrupted();
                    digest.update(buffer, 0, size);
                }
            }
            var identity = new Identity(path, before.fileKey(), before.size(), before.lastModifiedTime(),
                    HexFormat.of().formatHex(digest.digest()));
            if (!identity.current()) {
                throw new IOException("音频文件已变更");
            }
            return identity;
        }

        boolean current() throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return attrs.isRegularFile() && attrs.size() == length && attrs.lastModifiedTime().equals(modified)
                    && java.util.Objects.equals(attrs.fileKey(), fileKey);
        }
    }

    record Entry(Path path, int rate, int frames, float[] peaks, LoudnessProfile loudness, boolean quick) {
        Entry {
            peaks = peaks.clone();
        }

        @Override
        public float[] peaks() {
            return peaks.clone();
        }

        float peak(int track) {
            return peaks[track];
        }
    }

    record Window(Entry entry, int start, float[] samples) {
        boolean covers(long position, int frames) {
            return position >= start && position - start <= samples.length / FLOATS_PER_FRAME - frames;
        }

        int end() {
            return start + samples.length / FLOATS_PER_FRAME;
        }
    }

    record CachedTrack(String digest, String title, long bytes, FileTime lastUsed, boolean quick, boolean standard) {
        String displayName() {
            return title.isBlank() ? "旧缓存 " + digest.substring(0, 12) : title;
        }
    }

    synchronized Entry lookup(Identity id) throws IOException {
        return lookup(id, "");
    }

    synchronized Entry lookup(Identity id, String title) throws IOException {
        IOException failure = null;
        // Prefer the standard result and use the most recent file if duplicates exist.
        var candidates = entries().stream().filter(path -> {
            var name = CacheFileName.parse(path);
            return name.model().equals(CacheFileName.CURRENT_MODEL) && name.digest().equals(id.digest);
        }).sorted(Comparator.comparing((Path path) -> CacheFileName.parse(path).quick())
                .thenComparing(StemCache::lastUsed, Comparator.reverseOrder())).toList();
        for (Path candidate : candidates) {
            try {
                Path path = nameCacheFile(candidate, title);
                Entry entry = readJema(path);
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
                return entry;
            } catch (IOException error) {
                checkInterrupted();
                if (failure == null) failure = error;
            }
        }
        if (failure != null) throw failure;
        return null;
    }

    Entry publish(Identity id, int rate, int frames, Path raw, Path original, Path staging, long limit, boolean quick)
            throws IOException {
        return publish(id, "", rate, frames, raw, original, staging, limit, quick);
    }

    Entry publish(Identity id, String title, int rate, int frames, Path raw, Path original, Path staging, long limit,
                  boolean quick) throws IOException {
        long dataBytes = (long) frames * BYTES_PER_FRAME;
        if (frames <= 0 || rate < 8000 || rate > 384000 || Files.size(raw) != dataBytes || (original != null
                && Files.size(original) != (long) frames * AudioFormat.FLOAT_FRAME_BYTES) || !id.current()) {
            throw new IOException("分离输出不完整或音频文件已变更");
        }
        if (!id.digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid source identity");
        var name = new CacheFileName(null, CacheFileName.CURRENT_MODEL, quick, id.digest);
        Path target = root.resolve(name.forTitle(title));
        Files.createDirectories(target.getParent());
        Path pending = staging.resolve("completed.jema");
        ByteBuffer metadata = ordered(JEMA_METADATA_BYTES);
        try (var input = FileChannel.open(raw, StandardOpenOption.READ);
             var reference = original == null ? null : FileChannel.open(original, StandardOpenOption.READ)) {
            var peaks = new float[MixSettings.STEMS];
            var analysis = new LoudnessProfile.Analysis(rate);
            scan(input, dataBytes, peaks, analysis, reference);
            metadata.putInt(MAGIC).putInt(JEMA_METADATA_VERSION);
            for (float peak : peaks) {
                metadata.putFloat(peak);
            }
            analysis.result().write(metadata);
        }
        jema.encode(raw, pending, rate, frames, metadata.array());
        long bytes = Files.size(pending);
        if (bytes > limit) {
            throw new IOException("缓存上限不足以保存这首歌");
        }
        Entry encoded = readJema(pending);
        if (encoded.rate != rate || encoded.frames != frames) {
            throw new IOException("JEMA 缓存长度或格式无效");
        }

        synchronized (this) {
            checkInterrupted();
            if (!root.equals(target.getParent())) {
                throw new IOException("缓存目录已变更");
            }
            if (!id.current()) {
                throw new IOException("音频文件已变更");
            }
            var replaced = new java.util.HashSet<Path>();
            for (Path file : entries()) {
                var existing = CacheFileName.parse(file);
                if (existing.key().equals(name.key())) replaced.add(file);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !replaced.contains(target)) {
                throw new IOException("缓存文件名已被占用");
            }
            evictExcept(limit - bytes, replaced);
            for (Path file : replaced) recycle(file);
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
        }
        return new Entry(target, rate, frames, encoded.peaks, encoded.loudness, quick);
    }

    Window readWindow(Entry entry, int start, int frames) throws IOException {
        if (start < 0 || frames <= 0 || frames > MAX_WINDOW_FRAMES || frames > entry.frames - start) {
            throw new IOException("音轨缓存帧范围无效");
        }
        var data = ByteBuffer.wrap(jema.readFrames(entry.path, entry.rate, entry.frames, start, frames))
                .order(ByteOrder.LITTLE_ENDIAN);
        var samples = new float[frames * FLOATS_PER_FRAME];
        data.asFloatBuffer().get(samples);
        for (float sample : samples) {
            if (!Float.isFinite(sample)) {
                throw new IOException("音轨缓存含有无效样本");
            }
        }
        return new Window(entry, start, samples);
    }

    synchronized long size() throws IOException {
        long total = 0;
        for (Path file : entries()) {
            total += Files.size(file);
        }
        return total;
    }

    synchronized List<CachedTrack> catalog(String namedDigest, String title) throws IOException {
        var groups = new HashMap<String, CachedTrack>();
        for (Path file : entries()) {
            var name = CacheFileName.parse(file);
            var info = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String digest = name.digest();
            boolean quick = name.quick();
            groups.merge(digest, new CachedTrack(digest, name.title(), info.size(), info.lastModifiedTime(), quick, !quick),
                    (first, second) -> new CachedTrack(digest, first.title.isBlank() ? second.title : first.title,
                            first.bytes + second.bytes,
                            first.lastUsed.compareTo(second.lastUsed) >= 0 ? first.lastUsed : second.lastUsed,
                            first.quick || second.quick, first.standard || second.standard));
        }
        var titles = readTitles(root);
        var updated = new Properties();
        updated.putAll(titles);
        updated.keySet().retainAll(groups.keySet());
        if (namedDigest != null && groups.containsKey(namedDigest) && !title.isBlank()) {
            updated.setProperty(namedDigest, title);
        }
        if (!titles.equals(updated)) {
            try {
                writeTitles(root, updated);
            } catch (IOException error) {
                LOG.log(System.Logger.Level.WARNING, "Could not save cache titles", error);
            }
        }
        return groups.values().stream()
                .sorted(Comparator.comparing(CachedTrack::lastUsed).reversed().thenComparing(CachedTrack::digest))
                .map(track -> new CachedTrack(track.digest, updated.getProperty(track.digest, track.title), track.bytes,
                        track.lastUsed, track.quick, track.standard))
                .toList();
    }

    static Properties readTitles(Path root) {
        var titles = new Properties();
        Path index = root.resolve("titles.properties");
        if (Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
            try (var input = Files.newInputStream(index)) {
                titles.load(input);
            } catch (IOException | IllegalArgumentException error) {
                LOG.log(System.Logger.Level.WARNING, "Could not read cache titles", error);
            }
        }
        return titles;
    }

    static void writeTitles(Path root, Properties titles) throws IOException {
        Path pending = root.resolve("titles.pending");
        try (var output = Files.newOutputStream(pending, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            titles.store(output, null);
        }
        Files.move(pending, root.resolve("titles.properties"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    synchronized void clear(String digest) throws IOException {
        if (!digest.isEmpty() && !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid source identity");
        }
        for (Path file : entries()) {
            if (digest.isEmpty() || digest.equals(CacheFileName.parse(file).digest())) recycle(file);
        }
    }

    synchronized void evict(long limit, Path keep) throws IOException {
        evictExcept(limit, keep == null ? Set.of() : Set.of(keep));
    }

    private void evictExcept(long limit, Set<Path> keep) throws IOException {
        List<Path> files = entries().stream()
                .filter(path -> !keep.contains(path))
                .sorted(Comparator.comparing(StemCache::lastUsed))
                .toList();
        long total = 0;
        for (Path file : files) {
            total += Files.size(file);
        }
        for (Path file : files) {
            if (total <= limit) {
                break;
            }
            long size = Files.size(file);
            recycle(file);
            total -= size;
        }
    }

    private Entry readJema(Path path) throws IOException {
        var info = jema.inspect(path);
        var metadata = ByteBuffer.wrap(info.metadata()).order(ByteOrder.LITTLE_ENDIAN);
        if (metadata.remaining() != JEMA_METADATA_BYTES || metadata.getInt() != MAGIC
                || metadata.getInt() != JEMA_METADATA_VERSION) {
            throw new IOException("JEMA 混音元数据不兼容");
        }
        var peaks = new float[MixSettings.STEMS];
        for (int track = 0; track < peaks.length; track++) {
            float peak = metadata.getFloat();
            if (!Float.isFinite(peak) || peak < 0) {
                throw new IOException("JEMA 混音元数据包含无效峰值");
            }
            peaks[track] = peak;
        }
        return new Entry(path, info.rate(), info.frames(), peaks, LoudnessProfile.read(metadata), isQuick(path));
    }

    private static boolean isQuick(Path path) {
        var name = CacheFileName.parse(path);
        return name != null && name.quick();
    }

    private static void scan(FileChannel input, long remaining, float[] peaks,
                             LoudnessProfile.Analysis analysis, FileChannel original) throws IOException {
        var chunk = ordered(CHUNK_BYTES);
        var source = original == null ? null : ordered(CHUNK_BYTES / FLOATS_PER_FRAME * 2);
        while (remaining > 0) {
            checkInterrupted();
            int size = (int) Math.min(chunk.capacity(), remaining);
            chunk.clear().limit(size);
            read(input, chunk);
            chunk.flip();
            if (source != null) {
                source.clear().limit(size / FLOATS_PER_FRAME * 2);
                read(original, source);
                source.flip();
            }
            for (int offset = 0; offset < size; offset += BYTES_PER_FRAME) {
                for (int stem = 0; stem < peaks.length; stem++) {
                    int index = offset + stem * 2 * Float.BYTES;
                    float max = Math.max(Math.abs(chunk.getFloat(index)), Math.abs(chunk.getFloat(index + 4)));
                    if (!Float.isFinite(max)) {
                        throw new IOException("音轨缓存含有无效样本");
                    }
                    peaks[stem] = Math.max(peaks[stem], max);
                }
                analysis.accept(chunk, offset, source, offset / FLOATS_PER_FRAME * 2);
            }
            remaining -= size;
        }
    }

    private List<Path> entries() throws IOException {
        return filesIn(root);
    }

    static List<Path> filesIn(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.list(root)) {
            return paths.filter(StemCache::isCacheFile).toList();
        }
    }

    private static boolean isCacheFile(Path path) {
        return CacheFileName.parse(path) != null
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    static void renameKnownFiles(Path root) throws IOException {
        var titles = readTitles(root);
        for (Path file : filesIn(root)) {
            nameCacheFile(file, titles.getProperty(CacheFileName.parse(file).digest(), ""));
        }
    }

    private static Path nameCacheFile(Path file, String title) {
        var name = CacheFileName.parse(file);
        if (title.isBlank() || name.prefix() != null) return file;
        try {
            return Files.move(file, file.resolveSibling(name.forTitle(title)));
        } catch (FileAlreadyExistsException ignored) {
            return file;
        } catch (IOException error) {
            LOG.log(System.Logger.Level.WARNING, "Could not rename cache " + file, error);
            return file;
        }
    }

    private void recycle(Path file) throws IOException {
        if (!root.equals(file.getParent()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unexpected cache entry " + file);
        }
        recycler.recycle(file);
    }

    private static FileTime lastUsed(Path file) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return FileTime.fromMillis(0);
        }
    }

    private static ByteBuffer ordered(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void read(FileChannel channel, ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            if (channel.read(data) < 0) {
                throw new EOFException("音轨缓存不完整");
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static void checkInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("操作已取消");
        }
    }

    @Override
    public void close() {
        jema.close();
    }
}
