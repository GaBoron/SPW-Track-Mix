package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class TrackMixJobs implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TrackMixJobs.class.getName());
    private static final long NOTICE_DURATION = TimeUnit.SECONDS.toNanos(8);

    enum Kind { STANDARD, QUICK, MAINTENANCE }

    record Progress(String stage, double fraction) {
        String label() {
            String text = switch (stage) {
                case "" -> "准备开始";
                case "runtime" -> "准备本地推理环境";
                case "download" -> "下载模型";
                case "verify" -> "校验模型";
                case "load" -> "载入模型";
                case "decode" -> "解码原曲";
                case "gpu32" -> "CUDA FP32 分离";
                case "gpu16" -> "CUDA FP16 分离";
                case "cpu" -> "CPU 分离";
                case "fallback" -> "切换推理设备或精度";
                case "finish", "cache", "done" -> "写入音轨缓存";
                case "cache-directory" -> "切换缓存目录";
                default -> "正在处理";
            };
            return fraction > 0.001 && fraction < 0.999 ? text + " " + Math.round(fraction * 100) + "%" : text;
        }
    }
    record Item(int id, String title, Kind kind, boolean running, boolean cancelling, Progress progress) {}
    record Snapshot(List<Item> items, boolean currentQueued, String notice, int noticeId) {
        String noticeAfter(int openedAfter) {
            return noticeId > openedAfter ? notice : "";
        }
    }
    record Result(String message, boolean success) {}

    @FunctionalInterface
    interface Work {
        String run(PythonEngine.Task task) throws IOException, InterruptedException;
    }

    private final Executor worker;
    private final Runnable refreshStorage;
    private final LongSupplier nanoTime;
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private int nextId;
    private Job active;
    private String notice = "";
    private int noticeId;
    private long noticeStarted;
    private boolean closed;

    TrackMixJobs(Executor worker, Runnable refreshStorage) {
        this(worker, refreshStorage, System::nanoTime);
    }

    TrackMixJobs(Executor worker, Runnable refreshStorage, LongSupplier nanoTime) {
        this.worker = worker;
        this.refreshStorage = refreshStorage;
        this.nanoTime = nanoTime;
    }

    synchronized void separate(String title, String digest, boolean quick, Work work) {
        if (!closed && !contains(digest)) {
            queue(title, digest, quick ? Kind.QUICK : Kind.STANDARD, work, null);
        }
    }

    synchronized void maintain(String title, Work work) {
        maintain(title, work, null);
    }

    synchronized boolean maintain(String title, Work work, Consumer<Result> finished) {
        if (closed || active != null) return false;
        queue(title, null, Kind.MAINTENANCE, work, finished);
        return true;
    }

    synchronized Snapshot snapshot(String digest) {
        var items = new ArrayList<Item>();
        if (active != null) {
            items.add(active.view(true));
        }
        for (Job job : waiting) {
            items.add(job.view(false));
        }
        String visibleNotice = nanoTime.getAsLong() - noticeStarted < NOTICE_DURATION ? notice : "";
        return new Snapshot(List.copyOf(items), digest != null && contains(digest), visibleNotice, noticeId);
    }

    private boolean contains(String digest) {
        return (active != null && digest.equals(active.digest))
                || waiting.stream().anyMatch(job -> digest.equals(job.digest));
    }

    private void queue(String title, String digest, Kind kind, Work work, Consumer<Result> finished) {
        waiting.addLast(new Job(++nextId, title, digest, kind, work, finished));
        notice = "";
        if (active == null) {
            startNext();
        }
    }

    private void startNext() {
        active = waiting.pollFirst();
        if (active != null) {
            Job next = active;
            worker.execute(() -> run(next));
        }
    }

    private void run(Job job) {
        synchronized (this) {
            job.thread = Thread.currentThread();
        }
        String result = "操作失败";
        boolean success = false;
        try {
            job.task.check();
            result = job.work.run(job.task);
            success = true;
        } catch (CancellationException | InterruptedException cancelled) {
            result = "已取消";
        } catch (Exception error) {
            result = job.task.cancelled() ? "已取消" : message(error);
            if (!job.task.cancelled()) {
                LOG.log(System.Logger.Level.WARNING, "Track Mix operation failed", error);
            }
        } finally {
            synchronized (this) {
                job.thread = null;
            }
            Thread.interrupted();
            finish(job, new Result(result, success));
        }
    }

    private void finish(Job job, Result result) {
        try {
            refreshStorage.run();
        } finally {
            synchronized (this) {
                notice = job.title + " · " + result.message;
                noticeId++;
                noticeStarted = nanoTime.getAsLong();
                active = null;
                if (!closed) {
                    startNext();
                }
            }
            if (job.finished != null) job.finished.accept(result);
        }
    }

    synchronized void cancel(int id) {
        if (active != null && (id == 0 || id == active.id)) {
            active.task.cancel();
            if (active.thread != null) {
                active.thread.interrupt();
            }
        } else {
            waiting.removeIf(job -> job.id == id);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        waiting.clear();
        cancel(0);
        active = null;
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class Job {
        private final int id;
        private final String title;
        private final String digest;
        private final Kind kind;
        private final Work work;
        private final Consumer<Result> finished;
        private volatile Progress progress = new Progress("", 0);
        private Thread thread;
        private final PythonEngine.Task task = new PythonEngine.Task((stage, fraction) ->
                progress = new Progress(stage, fraction));

        Job(int id, String title, String digest, Kind kind, Work work, Consumer<Result> finished) {
            this.id = id;
            this.title = title;
            this.digest = digest;
            this.kind = kind;
            this.work = work;
            this.finished = finished;
        }

        Item view(boolean running) {
            return new Item(id, title, kind, running, task.cancelled(), progress);
        }
    }
}
