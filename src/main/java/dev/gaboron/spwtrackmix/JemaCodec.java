package dev.gaboron.spwtrackmix;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class JemaCodec implements AutoCloseable {
    private static final String RESOURCE = "/trackmix/TrackMix.Jema.exe";
    private static final int INSPECT = 1;
    private static final int READ = 2;
    private final Path directory;
    private Path executable;
    private Connection reader;
    private boolean closed;

    JemaCodec(Path directory) {
        this.directory = directory;
    }

    record Info(int rate, int frames, byte[] metadata) {}

    void encode(Path raw, Path destination, int rate, int frames, byte[] metadata) throws IOException {
        StemCache.checkInterrupted();
        Path log = destination.resolveSibling("jema-error.txt");
        Process process = command("encode", raw.toString(), destination.toString(), Integer.toString(rate),
                Integer.toString(frames), Base64.getEncoder().encodeToString(metadata))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(log.toFile())
                .start();
        try {
            if (process.waitFor() != 0) {
                try (var errors = Files.newInputStream(log)) {
                    String message = new String(errors.readNBytes(2048), StandardCharsets.UTF_8).strip();
                    throw new IOException(message.isEmpty() ? "JEMA 编码失败" : "JEMA 编码失败 · " + message);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("JEMA 编码已取消");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.onExit().join();
            }
        }
    }

    Info inspect(Path path) throws IOException {
        return request(connection -> {
            connection.sendPath(INSPECT, path);
            connection.checkStatus();
            int rate = connection.readInt();
            int frames = connection.readInt();
            return new Info(rate, frames, connection.readBytes(connection.readInt(), 4096));
        });
    }

    byte[] readFrames(Path path, int rate, int frames, int start, int count) throws IOException {
        return request(connection -> {
            connection.sendPath(READ, path);
            connection.writeInt(start);
            connection.writeInt(count);
            connection.writeInt(rate);
            connection.writeInt(frames);
            connection.checkStatus();
            return connection.readBytes(Math.multiplyExact(count, StemCache.BYTES_PER_FRAME),
                    StemCache.MAX_WINDOW_FRAMES * StemCache.BYTES_PER_FRAME);
        });
    }

    @FunctionalInterface
    private interface Request<T> {
        T exchange(Connection connection) throws IOException;
    }

    private synchronized <T> T request(Request<T> request) throws IOException {
        StemCache.checkInterrupted();
        if (reader == null || !reader.process.isAlive()) {
            stopReader();
            Process process = command("serve")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            reader = new Connection(process, new DataInputStream(new BufferedInputStream(process.getInputStream(), 64 * 1024)),
                    new DataOutputStream(new BufferedOutputStream(process.getOutputStream())));
        }
        // Process pipes have no read deadline. Bound the wait on the background
        // caller and terminate the child if the exchange cannot complete.
        Connection connection = reader;
        var task = new FutureTask<>(() -> request.exchange(connection));
        Thread.ofVirtual().start(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof InvalidCache invalid) {
                throw invalid;
            }
            stopReader();
            throw new IOException("无法读取 JEMA 缓存", error.getCause());
        } catch (TimeoutException error) {
            stopReader();
            throw new IOException("读取 JEMA 缓存超时", error);
        } catch (InterruptedException error) {
            stopReader();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("JEMA 读取已取消");
        }
    }

    private record Connection(Process process, DataInputStream input, DataOutputStream output) {
        void sendPath(int operation, Path path) throws IOException {
            byte[] bytes = path.toString().getBytes(StandardCharsets.UTF_8);
            output.writeByte(operation);
            writeInt(bytes.length);
            output.write(bytes);
        }

        void checkStatus() throws IOException {
            output.flush();
            int status = input.readUnsignedByte();
            if (status == 1) {
                throw new InvalidCache(new String(readBytes(readInt(), 2048), StandardCharsets.UTF_8));
            }
            if (status != 0) {
                throw new IOException("无效的 JEMA 响应");
            }
        }

        byte[] readBytes(int length, int maximum) throws IOException {
            if (length < 0 || length > maximum) {
                throw new IOException("无效的 JEMA 响应长度");
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return bytes;
        }

        int readInt() throws IOException {
            return Integer.reverseBytes(input.readInt());
        }

        void writeInt(int value) throws IOException {
            output.writeInt(Integer.reverseBytes(value));
        }
    }

    private synchronized Path executable() throws IOException {
        if (closed) {
            throw new IOException("JEMA 编解码器已关闭");
        }
        if (executable != null) {
            return executable;
        }
        byte[] binary;
        try (var resource = JemaCodec.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IOException("插件包缺少 JEMA 编解码器");
            }
            binary = resource.readAllBytes();
        }
        Files.createDirectories(directory);
        Path target = directory.resolve("jema-" + digest(binary) + ".exe");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            Path pending = Files.createTempFile(directory, "jema-", ".pending");
            try {
                Files.write(pending, binary);
                Files.move(pending, target);
            } catch (FileAlreadyExistsException concurrentInstall) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw concurrentInstall;
                }
            } finally {
                if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                    WindowsRecycle.recycle(pending);
                }
            }
        }
        executable = target;
        return executable;
    }

    private ProcessBuilder command(String... arguments) throws IOException {
        var command = new ArrayList<String>(arguments.length + 1);
        command.add(executable().toString());
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command);
        builder.environment().put("DOTNET_BUNDLE_EXTRACT_BASE_DIR", directory.resolve("runtime").toString());
        return builder;
    }

    private static String digest(byte[] binary) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void stopReader() {
        Connection connection = reader;
        if (connection == null) return;
        reader = null;
        connection.process.destroyForcibly();
        connection.process.onExit().join();
        try (var input = connection.input; var output = connection.output) {
            // Release the local pipe handles after the child has stopped writing.
        } catch (IOException ignored) {
            // A terminated child may already have closed the pipes.
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        stopReader();
    }

    private static final class InvalidCache extends IOException {
        InvalidCache(String message) {
            super("JEMA 缓存无效 · " + message);
        }
    }
}
