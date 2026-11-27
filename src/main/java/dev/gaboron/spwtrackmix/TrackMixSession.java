package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

final class TrackMixSession implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TrackMixSession.class.getName());

    record Track(Path path, String title, String id) {
        boolean sameSource(Track other) {
            return other != null && path.equals(other.path) && id.equals(other.id);
        }
    }

    record Selection(Track track, StemCache.Identity identity, StemCache.Entry entry, String error) {}
    record Source(Path path, int channel) {}

    @FunctionalInterface
    interface PlaybackSource {
        Source current() throws ReflectiveOperationException;
    }

    record Status(Selection selection, MixSettings mix, boolean ready, TrackMixJobs.Snapshot queue,
                  String notice, long cacheBytes, boolean modelReady, long modelBytes) {
        boolean canSeparate() {
            return !queue.currentQueued() && selection != null && selection.identity != null
                    && (selection.entry == null || selection.entry.quick());
        }

        boolean canQuickSeparate() {
            return canSeparate() && selection.entry == null;
        }
    }

    record CacheCatalog(List<StemCache.CachedTrack> tracks, long bytes, String error) {}

    private record Playback(Selection selection, MixSettings mix) {
        private static final Playback EMPTY = new Playback(null, MixSettings.DEFAULT);

        StemCache.Entry entry() {
            return selection == null ? null : selection.entry;
        }
    }

    private final Path data;
    private final Path config;
    private final Path defaultCacheDirectory;
    private final Supplier<Track> currentTrack;
    private final PlaybackSource playbackSource;
    private final StemCache cache;
    private final PythonEngine engine;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
            task -> thread(task, "Track Mix state"));
    private final ScheduledExecutorService preloader = Executors.newSingleThreadScheduledExecutor(
            task -> thread(task, "Track Mix preload"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> thread(task, "Track Mix worker"));
    private final TrackMixJobs jobs = new TrackMixJobs(worker, this::refreshStorage);
    private final AtomicLong cacheRevision = new AtomicLong();
    private final Object configurationLock = new Object();
    private volatile TrackMixOptions options;
    private TrackMixOptions saved;
    private final Object playbackLock = new Object();
    private final Object preloadLock = new Object();
    private volatile Playback playback = Playback.EMPTY;
    private volatile boolean closed;
    private volatile boolean modelReady;
    private volatile long modelBytes;
    private volatile CacheCatalog catalog;
    private volatile String removingDigest;
    private volatile String notice = "";
    private volatile long seenCacheRevision = -1;
    private volatile BassSourceDsp effects;
    private BassLibrary bass;

    TrackMixSession(Path data, Supplier<Track> currentTrack) {
        this(data, currentTrack, () -> null);
    }

    TrackMixSession(Path data, Supplier<Track> currentTrack, PlaybackSource playbackSource) {
        this.data = data;
        this.currentTrack = currentTrack;
        this.playbackSource = playbackSource;
        config = data.resolve("settings.properties");
        defaultCacheDirectory = data.resolve("cache").toAbsolutePath().normalize();
        options = saved = TrackMixOptions.read(config);
        cache = new StemCache(options.cacheDirectory(defaultCacheDirectory),
                data.resolve("codec").toAbsolutePath().normalize(), WindowsRecycle::recycle);
        engine = new PythonEngine(data);
    }

    void start() {
        monitor.scheduleWithFixedDelay(this::poll, 0, 650, TimeUnit.MILLISECONDS);
        preloader.scheduleWithFixedDelay(this::prefetch, 0, 30, TimeUnit.MILLISECONDS);
        worker.execute(this::initializeCache);
    }

    void sourceOpened(int channel, String path) {
        try {
            Path source = Path.of(path);
            if (source.isAbsolute()) {
                attachSource(channel, source.normalize());
            }
        } catch (Throwable error) {
            LOG.log(System.Logger.Level.WARNING, "Track Mix left an unsupported source untouched", error);
        }
    }

    private synchronized void attachSource(int channel, Path path) throws Throwable {
        if (closed) return;
        if (effects == null) {
            effects = new BassSourceDsp(bass(), cache, this::mixFor, () -> playback.entry());
        }
        effects.attach(channel, path);
    }

    private void recoverPlaybackSource() {
        var current = playback.selection;
        if (closed || current == null || current.entry == null) {
            return;
        }
        try {
            var source = playbackSource.current();
            if (source != null && source.path.equals(current.track.path) && playback.selection == current) {
                attachSource(source.channel, source.path);
            }
        } catch (Throwable error) {
            LOG.log(System.Logger.Level.WARNING, "Track Mix could not attach to the already-open source", error);
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
        Playback current = playback;
        Selection selected = current.selection;
        var active = effects;
        var queue = jobs.snapshot(selected == null || selected.identity == null ? null : selected.identity.digest());
        var storage = catalog;
        return new Status(selected, current.mix, selected != null && active != null
                && active.ready(selected.track.path, selected.entry), queue, notice,
                storage == null ? 0 : storage.bytes, modelReady, modelBytes);
    }

    CacheCatalog cacheCatalog() {
        return catalog;
    }

    TrackMixOptions options() {
        return options;
    }

    Path cacheDirectory() {
        return cache.root();
    }

    boolean changeCacheDirectory(Path folder, Consumer<TrackMixJobs.Result> finished) {
        return jobs.maintain("缓存目录", task -> {
            task.report("cache-directory", 0);
            return migrateCacheDirectory(folder, task);
        }, finished);
    }

    private String migrateCacheDirectory(Path folder, PythonEngine.Task task) throws IOException {
        Path parent = folder == null ? null : folder.toRealPath();
        if (parent != null && Files.isDirectory(defaultCacheDirectory)
                && parent.equals(defaultCacheDirectory.toRealPath())) {
            parent = null;
        } else if (parent != null && parent.getFileName() != null
                && TrackMixOptions.CACHE_FOLDER.equalsIgnoreCase(parent.getFileName().toString())) {
            parent = parent.getParent();
        }
        Path currentDirectory = cache.root();
        if (java.util.Objects.equals(options.cacheParent(), parent)) {
            return "当前已使用此缓存目录";
        }
        Path directory = options.withCacheParent(parent).cacheDirectory(defaultCacheDirectory);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || (parent != null && !directory.equals(directory.toRealPath()))
                || !Files.isWritable(directory)) {
            throw new IOException("缓存目录不可写");
        }
        var migration = CacheDirectoryMigration.prepare(currentDirectory, directory, task);
        StemCache.renameKnownFiles(directory);
        synchronized (configurationLock) {
            task.check();
            var updated = options.withCacheParent(parent);
            updated.write(config);
            // No job can publish to the old directory during this maintenance task.
            // Invalidate monitor lookups and DSP windows together before reading the new directory.
            synchronized (preloadLock) {
                synchronized (playbackLock) {
                    cache.changeRoot(directory);
                    cacheRevision.incrementAndGet();
                    bypassCurrent("");
                    catalog = null;
                    options = saved = updated;
                }
            }
        }
        int retained = migration.recycleOriginals();
        String result = parent == null ? "已恢复默认缓存目录" : "已更新缓存目录";
        return retained == 0 ? result : result + "，原目录保留 " + retained + " 个缓存文件";
    }

    void setMix(UnaryOperator<MixSettings> change) {
        setMix(null, change);
    }

    void setMix(Track expected, UnaryOperator<MixSettings> change) {
        synchronized (playbackLock) {
            Selection before = playback.selection;
            Selection current = refreshSelection();
            if (before != null && current != null && before.track.sameSource(current.track)
                    && (expected == null || current.track.sameSource(expected))) {
                playback = new Playback(current, change.apply(playback.mix));
            }
        }
    }

    private MixSettings mixFor(StemCache.Entry entry) {
        Playback current = playback;
        return entry != null && current.entry() == entry ? current.mix : MixSettings.DEFAULT;
    }

    synchronized void setOptions(int limit, boolean quality, boolean cpu, boolean fp16) {
        if (closed) {
            return;
        }
        boolean changedLimit;
        synchronized (configurationLock) {
            changedLimit = options.cacheGiB() != limit;
            options = new TrackMixOptions(limit, quality, cpu, fp16, options.cacheParent());
        }
        if (changedLimit) {
            worker.execute(this::enforceLimit);
        }
    }

    void separate(boolean quick) {
        separate(null, quick);
    }

    void separate(Track expected, boolean quick) {
        Selection before = playback.selection;
        Selection selected = refreshSelection();
        if (before == null || selected == null || !before.track.sameSource(selected.track)
                || (expected != null && !selected.track.sameSource(expected))
                || selected.identity == null || (selected.entry != null && (quick || !selected.entry.quick()))) {
            return;
        }
        TrackMixOptions parameters = options;
        jobs.separate(selected.track.title, selected.identity.digest(), quick,
                task -> separateTrack(selected, parameters, quick, task));
    }

    void prepareModel() {
        jobs.maintain("BS-RoFormer SW Fixed", task -> {
            engine.prepareModel(task);
            return "模型已就绪";
        });
    }

    void removeModel() {
        jobs.maintain("模型", task -> {
            engine.removeModel();
            return "模型文件已移入回收站";
        });
    }

    void clearCurrent() {
        Selection selected = refreshSelection();
        if (selected != null && selected.identity != null) {
            clearCache(selected.identity.digest(), selected.track.title);
        }
    }

    void clearAll() {
        clearCache("", "全部歌曲");
    }

    void clearCachedTrack(StemCache.CachedTrack track) {
        clearCache(track.digest(), track.displayName());
    }

    private void clearCache(String digest, String title) {
        jobs.maintain(title, task -> {
            removingDigest = digest;
            cacheRevision.incrementAndGet();
            try {
                bypassCurrent(digest);
                cache.clear(digest);
                return "音轨缓存已移入回收站";
            } finally {
                removingDigest = null;
                cacheRevision.incrementAndGet();
            }
        });
    }

    private String separateTrack(Selection selected, TrackMixOptions parameters, boolean quick, PythonEngine.Task task)
            throws IOException, InterruptedException {
        // Staging and cache publication must be on the same volume for the final atomic move.
        Path stagingParent = cache.root().resolve("jobs");
        Files.createDirectories(stagingParent);
        Path staging = Files.createTempDirectory(stagingParent, "separate-");
        try {
            Path input = staging.resolve("source.f32");
            Path output = staging.resolve("stems.f32");
            if (!selected.identity.current()) {
                throw new IOException("音频已变更，请重试");
            }
            task.report("decode", 0);
            AudioFormat format = bass().decode(selected.track.path, input,
                    progress -> task.report("decode", progress));
            task.check();
            engine.separate(task, input, output, format.rate(), parameters, quick);
            task.check();
            task.report("cache", 0);
            StemCache.Entry entry;
            try {
                entry = cache.publish(selected.identity, selected.track.title, format.rate(), format.frames(), output, input, staging,
                        options.cacheBytes(), quick);
            } catch (IOException error) {
                cacheRevision.incrementAndGet();
                throw error;
            }
            cachePublished(selected.identity, entry);
            refreshCatalog(selected.identity.digest(), selected.track.title);
            return quick ? "快速分离已完成" : "标准分离已完成";
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

    void cachePublished(StemCache.Identity identity, StemCache.Entry entry) {
        synchronized (playbackLock) {
            long revision = cacheRevision.incrementAndGet();
            var current = refreshSelection();
            if (current != null && identity.equals(current.identity)) {
                publishCacheSelection(current, new Selection(current.track, identity, entry, ""), revision);
            }
        }
        recoverPlaybackSource();
    }

    void cancel(int id) {
        jobs.cancel(id);
    }

    private Selection refreshSelection() {
        synchronized (playbackLock) {
            if (closed) {
                return null;
            }
            Track now = currentTrack.get();
            Selection previous = playback.selection;
            if (now == null) {
                playback = Playback.EMPTY;
            } else if (previous == null || !now.sameSource(previous.track)) {
                playback = new Playback(new Selection(now, null, null, ""), MixSettings.DEFAULT);
            } else if (!now.equals(previous.track)) {
                playback = new Playback(new Selection(now, previous.identity, previous.entry, previous.error), playback.mix);
            }
            return playback.selection;
        }
    }

    private boolean publishSelection(Selection previous, Selection updated) {
        synchronized (playbackLock) {
            if (closed || playback.selection != previous) {
                return false;
            }
            playback = new Playback(updated, playback.mix);
            return true;
        }
    }

    private boolean publishCacheSelection(Selection previous, Selection updated, long revision) {
        synchronized (playbackLock) {
            if (revision != cacheRevision.get() || !publishSelection(previous, updated)) return false;
            seenCacheRevision = revision;
            return true;
        }
    }

    private void poll() {
        if (closed) {
            return;
        }
        saveSettings();
        Selection selected = playback.selection;
        try {
            selected = refreshSelection();
            if (selected == null) {
                return;
            }
            Track track = selected.track;
            StemCache.Identity identity = selected.identity;
            long revision = cacheRevision.get();
            if (identity == null || !identity.current()) {
                if (identity != null) {
                    var pending = new Selection(track, null, null, "");
                    if (!publishSelection(selected, pending)) {
                        return;
                    }
                    selected = pending;
                }
                identity = StemCache.Identity.read(track.path);
            } else if (revision == seenCacheRevision) {
                return;
            }
            if (removes(identity, removingDigest)) return;
            Selection result;
            try {
                result = new Selection(track, identity, cache.lookup(identity, track.title), "");
            } catch (IOException error) {
                result = new Selection(track, identity, null, display(error));
            }
            if (publishCacheSelection(selected, result, revision)) {
                rememberCacheTitle(identity.digest(), track.title);
            }
        } catch (Exception error) {
            if (selected != null) {
                publishSelection(selected, new Selection(selected.track, null, null, display(error)));
            }
        } finally {
            recoverPlaybackSource();
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


    private void prefetch() {
        synchronized (preloadLock) {
            var active = effects;
            var current = playback.selection;
            if (active == null) return;
            try {
                active.prefetch(current == null ? null : current.track.path, current == null ? null : current.entry);
            } catch (IOException error) {
                if (current != null) {
                    publishSelection(current, new Selection(current.track, current.identity, null, display(error)));
                }
            }
        }
    }

    private void enforceLimit() {
        try {
            // Drop published windows before any eviction.
            if (cache.size() > options.cacheBytes()) {
                bypassCurrent("");
                try {
                    cache.evict(options.cacheBytes(), null);
                } finally {
                    cacheRevision.incrementAndGet();
                }
            }
        } catch (IOException error) {
            notice = display(error);
        } finally {
            refreshStorage();
        }
    }

    private void initializeCache() {
        try {
            synchronized (preloadLock) {
                synchronized (playbackLock) {
                    StemCache.renameKnownFiles(cache.root());
                    cacheRevision.incrementAndGet();
                }
            }
        } catch (IOException error) {
            LOG.log(System.Logger.Level.WARNING, "Could not prepare readable cache names", error);
        }
        enforceLimit();
    }

    private void bypassCurrent(String digest) {
        synchronized (preloadLock) {
            synchronized (playbackLock) {
                var selected = playback.selection;
                if (selected == null || !removes(selected.identity, digest)) return;
                playback = new Playback(new Selection(selected.track, selected.identity, null, ""), playback.mix);
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
    }

    private static boolean removes(StemCache.Identity identity, String digest) {
        return digest != null && (digest.isEmpty() || (identity != null && digest.equals(identity.digest())));
    }

    private void refreshStorage() {
        modelReady = engine.hasModel();
        modelBytes = engine.modelBytes();
        refreshCatalog(null, "");
    }

    private void refreshCatalog(String digest, String title) {
        try {
            var tracks = cache.catalog(digest, title);
            catalog = new CacheCatalog(tracks, tracks.stream().mapToLong(StemCache.CachedTrack::bytes).sum(), "");
        } catch (IOException error) {
            var previous = catalog;
            catalog = new CacheCatalog(previous == null ? List.of() : previous.tracks,
                    previous == null ? 0 : previous.bytes, "无法读取缓存列表");
            LOG.log(System.Logger.Level.WARNING, "Could not read Track Mix cache catalog", error);
        }
    }

    private void rememberCacheTitle(String digest, String title) {
        if (title.isBlank() || !needsTitle(catalog, digest)) return;
        worker.execute(() -> {
            if (needsTitle(catalog, digest)) refreshCatalog(digest, title);
        });
    }

    private static boolean needsTitle(CacheCatalog catalog, String digest) {
        if (catalog == null) return false;
        for (var track : catalog.tracks) {
            if (track.digest().equals(digest)) return track.title().isBlank();
        }
        return false;
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
        jobs.close();
        monitor.shutdownNow();
        preloader.shutdownNow();
        worker.shutdownNow();
        cache.close();
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
}
