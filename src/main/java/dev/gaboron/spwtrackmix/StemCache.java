package dev.gaboron.spwtrackmix;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
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
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

final class StemCache implements AutoCloseable {
    static final int FLOATS_PER_FRAME = MixSettings.STEMS * 2;
    static final int BYTES_PER_FRAME = FLOATS_PER_FRAME * Float.BYTES;
    static final int MAX_WINDOW_FRAMES = 192_000;
    private static final int MAGIC = 0x58494d54;
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION = 2;
    private static final int BASE_HEADER_BYTES = 48;
    private static final int LEGACY_HEADER_BYTES = BASE_HEADER_BYTES + LoudnessProfile.BYTES;
    private static final int JEMA_METADATA_VERSION = 1;
    private static final int JEMA_METADATA_BYTES = 2 * Integer.BYTES + MixSettings.STEMS * Float.BYTES
            + LoudnessProfile.BYTES;
    private static final int CHUNK_BYTES = BYTES_PER_FRAME * 16384;
    private static final String MODEL = "a443a298-";
    private static final Pattern CACHE_NAME = Pattern.compile("[a-f0-9]{8}-(quick-)?([a-f0-9]{64})\\.(?:jema|stems)");
    private static final System.Logger LOG = System.getLogger(StemCache.class.getName());
    private final Path root;
    private final Recycler recycler;
    private final JemaCodec jema;

    @FunctionalInterface
    interface Recycler {
        void recycle(Path path) throws IOException;
    }

    StemCache(Path root, Recycler recycler) {
        this.root = root.toAbsolutePath().normalize();
        this.recycler = recycler;
        this.jema = new JemaCodec(this.root.resolveSibling("codec"));
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

    record Entry(Path path, int rate, int frames, float[] peaks, int legacyHeaderBytes,
                 LoudnessProfile loudness, boolean quick) {
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
        IOException failure = null;
        // Keep the best separation quality, preferring JEMA within each quality.
        for (Path path : List.of(entryPath(id, false, ".jema"), entryPath(id, false, ".stems"),
                entryPath(id, true, ".jema"), entryPath(id, true, ".stems"))) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Entry entry = path.toString().endsWith(".jema") ? readJema(path) : verifyLegacy(path);
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
        long dataBytes = (long) frames * BYTES_PER_FRAME;
        if (frames <= 0 || rate < 8000 || rate > 384000 || Files.size(raw) != dataBytes || (original != null
                && Files.size(original) != (long) frames * AudioFormat.FLOAT_FRAME_BYTES) || !id.current()) {
            throw new IOException("分离输出不完整或音频文件已变更");
        }
        Files.createDirectories(root);
        Path target = entryPath(id, quick, ".jema");
        Path pending = staging.resolve("completed.jema");
        ByteBuffer metadata = ordered(JEMA_METADATA_BYTES);
        try (var input = FileChannel.open(raw, StandardOpenOption.READ);
             var reference = original == null ? null : FileChannel.open(original, StandardOpenOption.READ)) {
            var peaks = new float[MixSettings.STEMS];
            var analysis = new LoudnessProfile.Analysis(rate);
            scan(input, dataBytes, null, peaks, analysis, reference);
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
            if (!id.current()) {
                throw new IOException("音频文件已变更");
            }
            evict(limit - bytes, target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                recycle(target);
            }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
        }
        return new Entry(target, rate, frames, encoded.peaks, 0, encoded.loudness, quick);
    }

    Window readWindow(Entry entry, int start, int frames) throws IOException {
        if (start < 0 || frames <= 0 || frames > MAX_WINDOW_FRAMES || frames > entry.frames - start) {
            throw new IOException("音轨缓存帧范围无效");
        }
        ByteBuffer data;
        if (entry.legacyHeaderBytes == 0) {
            data = ByteBuffer.wrap(jema.readFrames(entry.path, entry.rate, entry.frames, start, frames))
                    .order(ByteOrder.LITTLE_ENDIAN);
        } else {
            data = ordered(Math.multiplyExact(frames, BYTES_PER_FRAME));
            try (var file = FileChannel.open(entry.path, StandardOpenOption.READ)) {
                file.position(entry.legacyHeaderBytes + (long) start * BYTES_PER_FRAME);
                read(file, data);
            }
            data.flip();
        }
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
            var name = CACHE_NAME.matcher(file.getFileName().toString());
            if (!name.matches()) continue;
            var info = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String digest = name.group(2);
            boolean quick = name.group(1) != null;
            groups.merge(digest, new CachedTrack(digest, "", info.size(), info.lastModifiedTime(), quick, !quick),
                    (first, second) -> new CachedTrack(digest, "", first.bytes + second.bytes,
                            first.lastUsed.compareTo(second.lastUsed) >= 0 ? first.lastUsed : second.lastUsed,
                            first.quick || second.quick, first.standard || second.standard));
        }
        var titles = readTitles();
        var updated = new Properties();
        updated.putAll(titles);
        updated.keySet().retainAll(groups.keySet());
        if (namedDigest != null && groups.containsKey(namedDigest) && !title.isBlank()) {
            updated.setProperty(namedDigest, title);
        }
        if (!titles.equals(updated)) writeTitles(updated);
        return groups.values().stream()
                .sorted(Comparator.comparing(CachedTrack::lastUsed).reversed().thenComparing(CachedTrack::digest))
                .map(track -> new CachedTrack(track.digest, updated.getProperty(track.digest, ""), track.bytes,
                        track.lastUsed, track.quick, track.standard))
                .toList();
    }

    private Properties readTitles() {
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

    private void writeTitles(Properties titles) {
        Path pending = root.resolve("titles.pending");
        try {
            try (var output = Files.newOutputStream(pending, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                titles.store(output, null);
            }
            Files.move(pending, root.resolve("titles.properties"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            LOG.log(System.Logger.Level.WARNING, "Could not save cache titles", error);
        }
    }

    synchronized void clear(String digest) throws IOException {
        if (!digest.isEmpty() && !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid source identity");
        }
        for (Path file : entries()) {
            String name = file.getFileName().toString();
            if (name.endsWith(digest + ".jema") || name.endsWith(digest + ".stems")) recycle(file);
        }
    }

    synchronized void evict(long limit, Path keep) throws IOException {
        List<Path> files = entries().stream()
                .filter(path -> !path.equals(keep))
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
        return new Entry(path, info.rate(), info.frames(), peaks, 0, LoudnessProfile.read(metadata), isQuick(path));
    }

    private Entry verifyLegacy(Path path) throws IOException {
        try (var input = FileChannel.open(path, StandardOpenOption.READ)) {
            var header = ordered(BASE_HEADER_BYTES);
            read(input, header);
            header.flip();
            int magic = header.getInt();
            int version = header.getInt();
            if (magic != MAGIC || (version != LEGACY_VERSION && version != VERSION)) {
                throw new IOException("音轨缓存版本不匹配");
            }
            int rate = header.getInt();
            int frames = header.getInt();
            long checksum = header.getLong();
            var peaks = new float[MixSettings.STEMS];
            for (int track = 0; track < peaks.length; track++) {
                peaks[track] = header.getFloat();
            }
            long dataBytes = (long) frames * BYTES_PER_FRAME;
            int headerBytes = version == VERSION ? LEGACY_HEADER_BYTES : BASE_HEADER_BYTES;
            if (rate < 8000 || rate > 384000 || frames <= 0 || input.size() != headerBytes + dataBytes) {
                throw new IOException("音轨缓存长度或格式无效");
            }
            var actual = new CRC32C();
            var actualPeaks = new float[MixSettings.STEMS];
            LoudnessProfile profile = null;
            if (version == VERSION) {
                var metadata = ordered(LoudnessProfile.BYTES);
                read(input, metadata);
                metadata.flip();
                profile = LoudnessProfile.read(metadata);
            }
            // Version 1 never stored the source reference, so estimate it from the six stems.
            var analysis = profile == null ? new LoudnessProfile.Analysis(rate) : null;
            scan(input, dataBytes, actual, actualPeaks, analysis, null);
            if (actual.getValue() != checksum || !java.util.Arrays.equals(peaks, actualPeaks)) {
                throw new IOException("音轨缓存完整性检查失败");
            }
            return new Entry(path, rate, frames, peaks, headerBytes,
                    profile == null ? analysis.result() : profile, isQuick(path));
        }
    }

    private static boolean isQuick(Path path) {
        return path.getFileName().toString().startsWith(MODEL + "quick-");
    }

    private static void scan(FileChannel input, long remaining, CRC32C crc, float[] peaks,
                             LoudnessProfile.Analysis analysis, FileChannel original) throws IOException {
        var chunk = ordered(CHUNK_BYTES);
        var source = original == null ? null : ordered(CHUNK_BYTES / FLOATS_PER_FRAME * 2);
        while (remaining > 0) {
            checkInterrupted();
            int size = (int) Math.min(chunk.capacity(), remaining);
            chunk.clear().limit(size);
            read(input, chunk);
            chunk.flip();
            if (crc != null) crc.update(chunk.array(), 0, size);
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
                if (analysis != null) {
                    analysis.accept(chunk, offset, source, offset / FLOATS_PER_FRAME * 2);
                }
            }
            remaining -= size;
        }
    }

    private List<Path> entries() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.list(root)) {
            return paths.filter(StemCache::isCacheFile).toList();
        }
    }

    private static boolean isCacheFile(Path path) {
        return CACHE_NAME.matcher(path.getFileName().toString()).matches()
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private Path entryPath(Identity id, boolean quick, String extension) {
        if (!id.digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid source identity");
        }
        return root.resolve(MODEL + (quick ? "quick-" : "") + id.digest + extension);
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
