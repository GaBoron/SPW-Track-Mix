package dev.gaboron.spwtrackmix;

import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.hook.HookHandle;
import com.xuncorp.spw.workshop.api.hook.MethodHook;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

public final class TrackMixPlugin extends SpwPlugin {
    private static final System.Logger LOG = System.getLogger(TrackMixPlugin.class.getName());
    private static final String BASS_BINDING = "com.moriafly.bass.BASS";
    // The local file factory in the Windows Steam 1.19.3 installation.
    private static final String CREATE_FILE_STREAM = "\u037f";
    private static final String HOST_FILE_DECODER = "com.xuncorp.pisces.bass.impl.\u052a";
    private static final StackWalker STACK = StackWalker.getInstance();
    private static volatile TrackMixHostUi mixerWindow;

    private HookHandle streamHook;
    private TrackMixSession session;
    private TrackMixLink link;
    private TrackMixHostUi window;

    public TrackMixPlugin(PluginContext context) {
        super(context);
    }

    public static void openMixer() {
        var active = mixerWindow;
        if (active != null) {
            active.open();
        } else {
            WorkshopApi.ui().toast("请在 Windows x64 Steam 版 SPW 1.19.3 中启用 Track Mix",
                    WorkshopApi.Ui.ToastType.Warning);
        }
    }

    public static void chooseCacheDirectory() {
        var active = mixerWindow;
        if (active != null) active.getCacheDirectories().choose();
        else WorkshopApi.ui().toast("请先启用 SPW Track Mix", WorkshopApi.Ui.ToastType.Warning);
    }

    public static void resetCacheDirectory() {
        var active = mixerWindow;
        if (active != null) active.getCacheDirectories().reset();
        else WorkshopApi.ui().toast("请先启用 SPW Track Mix", WorkshopApi.Ui.ToastType.Warning);
    }

    @Override
    public synchronized void start() {
        var context = getPluginContext();
        if (session != null) {
            return;
        }
        if (!"1.19.3".equals(context.getSpwVersion())
                || context.getSpwChannel() != Channel.Steam
                || !System.getProperty("os.name", "").startsWith("Windows")
                || !"amd64".equals(System.getProperty("os.arch"))) {
            LOG.log(System.Logger.Level.INFO, "Track Mix supports Steam SPW 1.19.3 on Windows x64");
            return;
        }

        String location = System.getenv("LOCALAPPDATA");
        if (location == null) {
            throw new IllegalStateException("找不到 Windows 本地应用数据目录");
        }
        var created = new TrackMixSession(Path.of(location, "SPW Track Mix").toAbsolutePath().normalize(),
                TrackMixPlugin::currentTrack, playbackSource());
        try {
            streamHook = WorkshopApi.hookRegistrar().hookMethod(BASS_BINDING, CREATE_FILE_STREAM,
                    List.of("java.lang.String", "long", "long", "int"), new MethodHook() {
                        @Override
                        public void after(MethodHookParam call) {
                            if (!call.isOriginalSkipped() && call.getThrowable() == null
                                    && call.getResult() instanceof Integer channel && channel != 0) {
                                Object[] args = call.getArgs();
                                if (args[0] instanceof String path && (long) args[1] == 0 && (long) args[2] == 0
                                        && STACK.walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(HOST_FILE_DECODER)))) {
                                    created.sourceOpened(channel, path);
                                }
                            }
                        }
                    });

            window = new TrackMixHostUi(created);
            window.install();
            created.start();
            link = new TrackMixLink(created, window.getCacheDirectories());
            session = created;
            mixerWindow = window;
        } catch (RuntimeException | Error error) {
            try {
                unhookAudio(streamHook);
            } finally {
                streamHook = null;
                try {
                    if (window != null) {
                        window.close();
                        window = null;
                    }
                } finally {
                    created.close();
                }
            }
            throw error;
        }
    }

    private static TrackMixSession.PlaybackSource playbackSource() {
        try {
            return new HostPlaybackSource(WorkshopApi.class.getClassLoader())::current;
        } catch (ReflectiveOperationException | LinkageError error) {
            LOG.log(System.Logger.Level.WARNING, "Could not resolve the already-open SPW playback source", error);
            return () -> null;
        }
    }

    private static TrackMixSession.Track currentTrack() {
        var track = WorkshopApi.playback().getCurrentMediaItem();
        if (track == null || track.getPath().isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(track.getPath()).normalize();
            if (path.isAbsolute() && path.getFileName() != null) {
                String title = track.getTitle().isBlank() ? path.getFileName().toString() : track.getTitle();
                return new TrackMixSession.Track(path, title, track.getId());
            }
        } catch (InvalidPathException ignored) {
            // URI-backed and external playback do not have a native local file stream.
        }
        return null;
    }

    @Override
    public void stop() {
        HookHandle hook;
        TrackMixSession active;
        TrackMixLink oldLink;
        TrackMixHostUi oldWindow;
        synchronized (this) {
            hook = streamHook;
            streamHook = null;
            active = session;
            session = null;
            oldLink = link;
            link = null;
            oldWindow = window;
            window = null;
            if (mixerWindow == oldWindow) {
                mixerWindow = null;
            }
        }

        try {
            unhookAudio(hook);
        } finally {
            try {
                if (oldWindow != null) {
                    oldWindow.close();
                }
            } finally {
                try {
                    if (oldLink != null) {
                        oldLink.close();
                    }
                } finally {
                    if (active != null) {
                        active.close();
                    }
                }
            }
        }
    }

    private static void unhookAudio(HookHandle source) {
        if (source != null) {
            source.unhook();
        }
    }

    @Override
    public void update() {
        stop();
    }

    @Override
    public void delete() {
        stop();
    }
}
