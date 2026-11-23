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
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32C;

final class StemCache {
    static final int FLOATS_PER_FRAME = MixSettings.STEMS * 2;
    static final int BYTES_PER_FRAME = FLOATS_PER_FRAME * Float.BYTES;
    private static final int MAGIC = 0x58494d54;
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION = 2;
    private static final int BASE_HEADER_BYTES = 48;
    static final int HEADER_BYTES = BASE_HEADER_BYTES + LoudnessProfile.BYTES;
    private static final int CHUNK_BYTES = BYTES_PER_FRAME * 16384;
    private static final String MODEL = "a443a298-";
    private final Path root;
    private final Recycler recycler;

    @FunctionalInterface
    interface Recycler {
        void recycle(Path path) throws IOException;
    }

    StemCache(Path root, Recycler recycler) {
        this.root = root.toAbsolutePath().normalize();
        this.recycler = recycler;
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

    record Entry(Path path, int rate, int frames, long crc, float[] peaks, int headerBytes, LoudnessProfile loudness) {
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

    Entry lookup(Identity id) throws IOException {
        Path path = entryPath(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Entry entry = verify(path);
        Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
        return entry;
    }

    Entry publish(Identity id, int rate, int frames, Path raw, Path original, Path staging, long limit)
            throws IOException {
        long dataBytes = (long) frames * BYTES_PER_FRAME;
        if (dataBytes + HEADER_BYTES > limit) {
            throw new IOException("缓存上限不足以保存这首歌");
        }
        if (Files.size(raw) != dataBytes || (original != null
                && Files.size(original) != (long) frames * AudioFormat.FLOAT_FRAME_BYTES) || !id.current()) {
            throw new IOException("分离输出不完整或音频文件已变更");
        }
        Files.createDirectories(root);
        Path target = entryPath(id);
        Path pending = staging.resolve("completed.stems");
        try (var input = FileChannel.open(raw, StandardOpenOption.READ);
             var reference = original == null ? null : FileChannel.open(original, StandardOpenOption.READ);
             var output = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var crc = new CRC32C();
            var peaks = new float[MixSettings.STEMS];
            output.position(HEADER_BYTES);
            var analysis = new LoudnessProfile.Analysis(rate);
            scan(input, output, dataBytes, crc, peaks, analysis, reference);
            output.position(0);
            ByteBuffer header = ordered(HEADER_BYTES);
            header.putInt(MAGIC).putInt(VERSION).putInt(rate).putInt(frames).putLong(crc.getValue());
            for (float peak : peaks) {
                header.putFloat(peak);
            }
            analysis.result().write(header);
            header.flip();
            write(output, header);
            output.force(true);
        }

        checkInterrupted();
        if (!id.current()) {
            throw new IOException("音频文件已变更");
        }
        evict(limit - (dataBytes + HEADER_BYTES), target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            recycle(target);
        }
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
        return verify(target);
    }

    static Window readWindow(Entry entry, int start, int frames) throws IOException {
        if (start < 0 || frames <= 0 || frames > entry.frames - start) {
            throw new IOException("音轨缓存帧范围无效");
        }
        ByteBuffer data = ordered(Math.multiplyExact(frames, BYTES_PER_FRAME));
        try (var file = FileChannel.open(entry.path, StandardOpenOption.READ)) {
            file.position(entry.headerBytes + (long) start * BYTES_PER_FRAME);
            read(file, data);
        }
        data.flip();
        var samples = new float[frames * FLOATS_PER_FRAME];
        data.asFloatBuffer().get(samples);
        for (float sample : samples) {
            if (!Float.isFinite(sample)) {
                throw new IOException("音轨缓存含有无效样本");
            }
        }
        return new Window(entry, start, samples);
    }

    long size() throws IOException {
        long total = 0;
        for (Path file : entries()) {
            total += Files.size(file);
        }
        return total;
    }

    void clear(Identity id) throws IOException {
        Path path = entryPath(id);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            recycle(path);
        }
    }

    void evict(long limit, Path keep) throws IOException {
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

    private Entry verify(Path path) throws IOException {
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
            int headerBytes = version == VERSION ? HEADER_BYTES : BASE_HEADER_BYTES;
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
            scan(input, null, dataBytes, actual, actualPeaks, analysis, null);
            if (actual.getValue() != checksum || !java.util.Arrays.equals(peaks, actualPeaks)) {
                throw new IOException("音轨缓存完整性检查失败");
            }
            return new Entry(path, rate, frames, checksum, peaks, headerBytes,
                    profile == null ? analysis.result() : profile);
        }
    }

    private static void scan(FileChannel input, FileChannel output, long remaining, CRC32C crc, float[] peaks,
                             LoudnessProfile.Analysis analysis, FileChannel original) throws IOException {
        var chunk = ordered(CHUNK_BYTES);
        var source = original == null ? null : ordered(CHUNK_BYTES / FLOATS_PER_FRAME * 2);
        while (remaining > 0) {
            checkInterrupted();
            int size = (int) Math.min(chunk.capacity(), remaining);
            chunk.clear().limit(size);
            read(input, chunk);
            chunk.flip();
            crc.update(chunk.array(), 0, size);
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
            if (output != null) {
                write(output, chunk);
            }
            remaining -= size;
        }
    }

    private List<Path> entries() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.list(root)) {
            return paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{8}-[a-f0-9]{64}\\.stems"))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .toList();
        }
    }

    private Path entryPath(Identity id) {
        if (!id.digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid source identity");
        }
        return root.resolve(MODEL + id.digest + ".stems");
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

    private static void write(FileChannel channel, ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            channel.write(data);
        }
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
}
