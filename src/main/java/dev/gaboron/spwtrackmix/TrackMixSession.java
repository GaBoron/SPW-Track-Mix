package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

final class TrackMixSession implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TrackMixSession.class.getName());

    record Track(Path path, String title) {}
    record Selection(Track track, StemCache.Identity identity, StemCache.Entry entry, String error) {}
    record Status(Selection selection, boolean attached, Job job, String notice, long cacheBytes, boolean modelReady) {}

    private final Path data;
    private final Path config;
    private final Supplier<Track> currentTrack;
    private final StemCache cache;
    private final PythonEngine engine;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
            task -> thread(task, "Track Mix state"));
    private final ScheduledExecutorService preloader = Executors.newSingleThreadScheduledExecutor(
            task -> thread(task, "Track Mix preload"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> thread(task, "Track Mix worker"));
    private final AtomicLong cacheRevision = new AtomicLong();
    private final AtomicBoolean seekPrefetchQueued = new AtomicBoolean();
    private final Object configurationLock = new Object();
    private volatile TrackMixOptions options;
    private TrackMixOptions saved;
    private volatile Selection selection;
    private volatile Job job;
    private volatile boolean closed;
    private volatile boolean modelReady;
    private volatile long cacheBytes;
    private volatile String notice = "";
    private long seenCacheRevision = -1;
    private volatile BassSourceDsp effects;
    private BassLibrary bass;

    TrackMixSession(Path data, Supplier<Track> currentTrack) {
        this.data = data;
        this.currentTrack = currentTrack;
        config = data.resolve("settings.properties");
        options = saved = TrackMixOptions.read(config);
        cache = new StemCache(data.resolve("cache"), WindowsRecycle::recycle);
        engine = new PythonEngine(data);
    }

    void start() {
        monitor.scheduleWithFixedDelay(this::poll, 0, 650, TimeUnit.MILLISECONDS);
        preloader.scheduleWithFixedDelay(this::prefetch, 0, 30, TimeUnit.MILLISECONDS);
        worker.execute(this::enforceLimit);
    }

    synchronized void sourceOpened(int channel, String path) {
        if (closed) {
            return;
        }
        try {
            Path source = Path.of(path);
            if (!source.isAbsolute()) {
                return;
            }
            if (effects == null) {
                effects = new BassSourceDsp(bass(), () -> options.mix(), () -> {
                    Selection current = selection;
                    return current == null ? null : current.entry;
                });
            }
            effects.attach(channel, source.normalize());
        } catch (Throwable error) {
            LOG.log(System.Logger.Level.WARNING, "Track Mix left an unsupported source untouched", error);
        }
    }

    private synchronized BassLibrary bass() {
        if (bass == null) {
            String resources = System.getProperty("compose.application.resources.dir");
            if (resources == null) {
                throw new IllegalStateException("宿主音频组件路径不可用");
            }
            bass = BassLibrary.resolveLoaded(Path.of(resources).toAbsolutePath().resolve("bass.dll"));
        }
        return bass;
    }

    Status status() {
        Selection selected = selection;
        var active = effects;
        return new Status(selected, selected != null && active != null
                && active.attached(selected.track.path, selected.entry), job, notice, cacheBytes, modelReady);
    }

    TrackMixOptions options() {
        return options;
    }

    void setMix(UnaryOperator<MixSettings> change) {
        synchronized (configurationLock) {
            options = options.withMix(change.apply(options.mix()));
        }
    }

    synchronized void setOptions(int limit, boolean quality, boolean cpu, boolean fp16) {
        if (closed) {
            return;
        }
        boolean changedLimit;
        synchronized (configurationLock) {
            changedLimit = options.cacheGiB() != limit;
            options = new TrackMixOptions(limit, quality, cpu, fp16, options.mix());
        }
        if (changedLimit) {
            worker.execute(this::enforceLimit);
        }
    }

    void separate() {
        Selection selected = selection;
        if (selected == null || selected.identity == null) {
            return;
        }
        TrackMixOptions parameters = options;
        submit(selected.track.title, task -> separateTrack(selected, parameters, task));
    }

    void prepareModel() {
        submit("BS-RoFormer SW Fixed", task -> {
            engine.prepareModel(task);
            return "模型已就绪";
        });
    }

    void removeModel() {
        submit("模型", task -> {
            engine.removeModel();
            return "模型文件已移入回收站";
        });
    }

    void clearCurrent() {
        Selection selected = selection;
        if (selected != null && selected.identity != null) {
            submit(selected.track.title, task -> {
                bypassCurrent();
                cache.clear(selected.identity);
                return "当前歌曲缓存已移入回收站";
            });
        }
    }

    void clearAll() {
        submit("音轨缓存", task -> {
            bypassCurrent();
            cache.evict(0, null);
            return "音轨缓存已移入回收站";
        });
    }

    private String separateTrack(Selection selected, TrackMixOptions parameters, PythonEngine.Task task)
            throws IOException, InterruptedException {
        Path stagingParent = data.resolve("jobs");
        Files.createDirectories(stagingParent);
        Path staging = Files.createTempDirectory(stagingParent, "separate-");
        try {
            Path input = staging.resolve("source.f32");
            Path output = staging.resolve("stems.f32");
            if (!selected.identity.current()) {
                throw new IOException("音频已变更，请重试");
            }
            task.report("decode", 0);
            AudioFormat format = bass().decode(selected.track.path, input, parameters.cacheBytes(),
                    progress -> task.report("decode", progress));
            task.check();
            engine.separate(task, input, output, format.rate(), parameters);
            task.check();
            task.report("cache", 0);
            cache.publish(selected.identity, format.rate(), format.frames(), output, input, staging, options.cacheBytes());
            return "六轨分离已完成";
        } finally {
            Thread.interrupted();
            if (staging.getParent().equals(stagingParent) && Files.isDirectory(staging)) {
                try {
                    WindowsRecycle.recycle(staging);
                } catch (IOException error) {
                    LOG.log(System.Logger.Level.WARNING, "Could not recycle " + staging, error);
                }
            }
        }
    }

    private synchronized void submit(String title, Work work) {
        if (closed || job != null) {
            return;
        }
        Job started = new Job(title);
        job = started;
        notice = "";
        worker.execute(() -> {
            started.thread = Thread.currentThread();
            try {
                started.task.check();
                notice = work.run(started.task);
            } catch (CancellationException | InterruptedException error) {
                notice = "已取消";
            } catch (Exception error) {
                notice = started.task.cancelled() ? "已取消" : display(error);
                if (!started.task.cancelled()) {
                    LOG.log(System.Logger.Level.WARNING, "Track Mix operation failed", error);
                }
            } finally {
                Thread.interrupted();
                started.thread = null;
                refreshStorage();
                job = null;
            }
        });
    }

    void cancel() {
        Job running = job;
        if (running != null) {
            running.task.cancel();
            Thread executing = running.thread;
            if (executing != null) {
                executing.interrupt();
            }
        }
    }

    private void poll() {
        if (closed) {
            return;
        }
        saveSettings();
        try {
            Track now = currentTrack.get();
            var old = selection;
            if (now == null) {
                selection = null;
                return;
            }
            var id = old != null && old.track.path.equals(now.path) && old.identity != null
                    && old.identity.current() ? old.identity : null;
            long revision = cacheRevision.get();
            if (id != null && revision == seenCacheRevision && old.track.equals(now)) {
                return;
            }
            if (id == null) {
                selection = new Selection(now, null, null, "");
                id = StemCache.Identity.read(now.path);
            }
            try {
                var entry = cache.lookup(id);
                if (revision == cacheRevision.get() && !closed) {
                    selection = new Selection(now, id, entry, "");
                    seenCacheRevision = revision;
                }
            } catch (IOException error) {
                selection = new Selection(now, id, null, display(error));
                seenCacheRevision = revision;
            }
        } catch (Exception error) {
            var old = selection;
            if (old != null) {
                selection = new Selection(old.track, null, null, display(error));
            }
        }
    }

    Object[] spectrum(String source, long positionMs) {
        var active = effects;
        if (active == null) {
            return null;
        }
        try {
            return active.spectrum(Path.of(source).toAbsolutePath().normalize(), positionMs);
        } catch (java.nio.file.InvalidPathException invalid) {
            return null;
        }
    }

    void sourceSeeking(int channel, long position) {
        var active = effects;
        if (active == null || !active.prepareSeek(channel, position) || !seekPrefetchQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            preloader.execute(() -> {
                seekPrefetchQueued.set(false);
                prefetch();
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            seekPrefetchQueued.set(false);
        }
    }

    private void prefetch() {
        var active = effects;
        var current = selection;
        if (active == null) {
            return;
        }
        try {
            active.prefetch(current == null ? null : current.track.path, current == null ? null : current.entry);
        } catch (IOException error) {
            if (current != null && selection == current) {
                selection = new Selection(current.track, current.identity, null, display(error));
            }
        }
    }

    private void enforceLimit() {
        try {
            // Drop published windows before any eviction.
            if (cache.size() > options.cacheBytes()) {
                bypassCurrent();
                cache.evict(options.cacheBytes(), null);
            }
        } catch (IOException error) {
            notice = display(error);
        } finally {
            refreshStorage();
        }
    }

    private void bypassCurrent() {
        var selected = selection;
        if (selected != null) {
            selection = new Selection(selected.track, selected.identity, null, "");
        }
        var active = effects;
        if (active != null) {
            try {
                active.prefetch(null, null);
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    private void refreshStorage() {
        modelReady = engine.hasModel();
        try {
            cacheBytes = cache.size();
        } catch (IOException error) {
            notice = display(error);
        }
        cacheRevision.incrementAndGet();
    }

    private void saveSettings() {
        synchronized (configurationLock) {
            if (options != saved) {
                var current = options;
                try {
                    current.write(config);
                    saved = current;
                } catch (IOException error) {
                    notice = "设置保存失败";
                    LOG.log(System.Logger.Level.WARNING, "Could not save Track Mix options", error);
                }
            }
        }
    }

    @Override
    public void close() {
        BassSourceDsp active;
        synchronized (this) {
            closed = true;
            active = effects;
            effects = null;
        }
        cancel();
        monitor.shutdownNow();
        preloader.shutdownNow();
        worker.shutdownNow();
        if (active != null) {
            active.close();
        }
        saveSettings();
    }

    private static Thread thread(Runnable work, String name) {
        var thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String display(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @FunctionalInterface
    private interface Work {
        String run(PythonEngine.Task task) throws IOException, InterruptedException;
    }

    static final class Job {
        final String title;
        volatile String stage = "";
        volatile double progress;
        volatile Thread thread;
        final PythonEngine.Task task = new PythonEngine.Task((stage, progress) -> {
            this.stage = stage;
            this.progress = progress;
        });

        Job(String title) {
            this.title = title;
        }
    }
}
