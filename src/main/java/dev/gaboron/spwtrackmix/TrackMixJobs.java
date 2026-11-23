package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

final class TrackMixJobs implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TrackMixJobs.class.getName());

    enum Kind { STANDARD, QUICK, MAINTENANCE }

    record Progress(String stage, double fraction) {}
    record Item(int id, String title, Kind kind, boolean running, boolean cancelling, Progress progress) {}
    record Snapshot(List<Item> items, boolean currentQueued, String notice) {}

    @FunctionalInterface
    interface Work {
        String run(PythonEngine.Task task) throws IOException, InterruptedException;
    }

    private final Executor worker;
    private final Runnable refreshStorage;
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private int nextId;
    private Job active;
    private String notice = "";
    private boolean closed;

    TrackMixJobs(Executor worker, Runnable refreshStorage) {
        this.worker = worker;
        this.refreshStorage = refreshStorage;
    }

    synchronized void separate(String title, String digest, boolean quick, Work work) {
        if (!closed && !contains(digest)) {
            queue(title, digest, quick ? Kind.QUICK : Kind.STANDARD, work);
        }
    }

    synchronized void maintain(String title, Work work) {
        if (!closed && active == null) {
            queue(title, null, Kind.MAINTENANCE, work);
        }
    }

    synchronized Snapshot snapshot(String digest) {
        var items = new ArrayList<Item>();
        if (active != null) {
            items.add(active.view(true));
        }
        for (Job job : waiting) {
            items.add(job.view(false));
        }
        return new Snapshot(List.copyOf(items), digest != null && contains(digest), notice);
    }

    private boolean contains(String digest) {
        return (active != null && digest.equals(active.digest))
                || waiting.stream().anyMatch(job -> digest.equals(job.digest));
    }

    private void queue(String title, String digest, Kind kind, Work work) {
        waiting.addLast(new Job(++nextId, title, digest, kind, work));
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
        try {
            job.task.check();
            result = job.work.run(job.task);
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
            finish(job, result);
        }
    }

    private void finish(Job job, String result) {
        try {
            refreshStorage.run();
        } finally {
            synchronized (this) {
                notice = job.title + " · " + result;
                active = null;
                if (!closed) {
                    startNext();
                }
            }
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
        private volatile Progress progress = new Progress("", 0);
        private Thread thread;
        private final PythonEngine.Task task = new PythonEngine.Task((stage, fraction) ->
                progress = new Progress(stage, fraction));

        Job(int id, String title, String digest, Kind kind, Work work) {
            this.id = id;
            this.title = title;
            this.digest = digest;
            this.kind = kind;
            this.work = work;
        }

        Item view(boolean running) {
            return new Item(id, title, kind, running, task.cancelled(), progress);
        }
    }
}
