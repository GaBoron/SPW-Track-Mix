package dev.gaboron.spwtrackmix;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;

final class PythonEngine {
    private static final String VERSION = "0.48.0";
    private final Path root;
    private final Path models;
    private final Path runtime;
    private final Path python;

    PythonEngine(Path root) {
        this.root = root;
        models = root.resolve("models");
        runtime = root.resolve("engine");
        python = runtime.resolve("python/Scripts/python.exe");
    }

    boolean hasModel() {
        try {
            return Files.size(models.resolve("BS-Rofo-SW-Fixed.ckpt")) == 699_412_152
                    && Files.size(models.resolve("BS-Rofo-SW-Fixed.yaml")) == 4613;
        } catch (IOException error) {
            return false;
        }
    }

    void prepareModel(Task task) throws IOException, InterruptedException {
        Path script = workerScript();
        run(task, List.of("py", "-3.13", "-u", script.toString(), "--models", models.toString()));
    }

    void removeModel() throws IOException {
        for (String name : List.of("BS-Rofo-SW-Fixed.ckpt", "BS-Rofo-SW-Fixed.yaml",
                "BS-Rofo-SW-Fixed.ckpt.part", "BS-Rofo-SW-Fixed.yaml.part")) {
            Path path = models.resolve(name);
            if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                WindowsRecycle.recycle(path);
            }
        }
    }

    void separate(Task task, Path input, Path output, int rate, TrackMixOptions options, boolean quick)
            throws IOException, InterruptedException {
        Path script = workerScript();
        ensureRuntime(task);
        var command = new ArrayList<>(List.of(python.toString(), "-u", script.toString(), "--models",
                models.toString(), "--input", input.toString(), "--output", output.toString(),
                "--rate", Integer.toString(rate)));
        if (options.cpu()) {
            command.add("--cpu");
        }
        if (options.fp16()) {
            command.add("--fp16");
        }
        if (quick) {
            command.add("--quick");
        } else if (options.quality()) {
            command.add("--quality");
        }
        run(task, command);
    }

    private Path workerScript() throws IOException {
        Files.createDirectories(runtime);
        Path script = runtime.resolve("separate.py");
        try (var resource = PythonEngine.class.getResourceAsStream("/trackmix/separate.py")) {
            if (resource == null) {
                throw new IOException("插件包缺少分离脚本");
            }
            Files.copy(resource, script, StandardCopyOption.REPLACE_EXISTING);
        }
        return script;
    }

    private void ensureRuntime(Task task) throws IOException, InterruptedException {
        if (!Files.isRegularFile(python)) {
            task.report("runtime", 0);
            run(task, List.of("py", "-3.13", "-m", "venv", runtime.resolve("python").toString()));
        }
        try {
            run(task, List.of(python.toString(), "-c",
                    "from importlib.metadata import version; assert version('audio-separator') == '" + VERSION
                            + "'; import torch; assert torch.__version__.split('+')[0] == '2.10.0'; import onnxruntime; import audioread"));
            return;
        } catch (WorkerFailure missing) {
            task.check();
        }

        task.report("runtime", 0);
        if (hasNvidia(task)) {
            try {
                installTorch(task, "cu128");
            } catch (WorkerFailure cudaUnavailable) {
                task.check();
                installTorch(task, "cpu");
            }
        } else {
            installTorch(task, "cpu");
        }
        run(task, List.of(python.toString(), "-m", "pip", "install", "--disable-pip-version-check",
                "audio-separator[cpu]==" + VERSION, "torch==2.10.0", "torchvision==0.25.0", "audioread==3.0.1"));
    }

    private boolean hasNvidia(Task task) throws InterruptedException {
        try {
            run(task, List.of("nvidia-smi", "--query-gpu=name", "--format=csv,noheader"));
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private void installTorch(Task task, String build) throws IOException, InterruptedException {
        run(task, List.of(python.toString(), "-m", "pip", "install", "--disable-pip-version-check",
                "--index-url", "https://download.pytorch.org/whl/" + build, "torch==2.10.0+" + build, "torchvision==0.25.0+" + build));
    }

    private void run(Task task, List<String> command) throws IOException, InterruptedException {
        task.check();
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("OMP_NUM_THREADS", "6");
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            if ("py".equals(command.getFirst())) {
                throw new IOException("无法启动 Python。请安装 64 位 Python 3.13 和 Py Launcher。", error);
            }
            throw error;
        }

        var recent = new ArrayDeque<String>();
        try {
            task.bind(process);
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    task.check();
                    if (line.startsWith("trackmix\t")) {
                        String[] event = line.split("\t", 3);
                        if (event.length == 3) {
                            task.report(event[1], Double.parseDouble(event[2]));
                        }
                    } else if (!line.isBlank()) {
                        if (recent.size() == 8) {
                            recent.removeFirst();
                        }
                        recent.addLast(line.substring(0, Math.min(500, line.length())));
                    }
                }
            }
            int exit = process.waitFor();
            task.check();
            if (exit != 0) {
                throw new WorkerFailure("分离进程退出 " + exit + "。 " + String.join(" ", recent));
            }
        } finally {
            task.unbind(process);
            if (process.isAlive()) {
                Task.terminate(process);
            }
        }
    }

    static class WorkerFailure extends IOException {
        WorkerFailure(String message) {
            super(message);
        }
    }

    static final class Task {
        private final BiConsumer<String, Double> events;
        private volatile boolean cancelled;
        private Process running;

        Task(BiConsumer<String, Double> events) {
            this.events = events;
        }

        void report(String state, double fraction) {
            if (!cancelled) {
                events.accept(state, fraction);
            }
        }

        boolean cancelled() {
            return cancelled;
        }

        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
        }

        synchronized void bind(Process child) {
            running = child;
            check();
        }

        synchronized void unbind(Process child) {
            if (running == child) {
                running = null;
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (running != null) {
                terminate(running);
            }
        }

        private static void terminate(Process process) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
