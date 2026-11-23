package dev.gaboron.spwtrackmix;

import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.hook.HookHandle;
import com.xuncorp.spw.workshop.api.hook.MethodHook;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;

import java.nio.file.Path;
import java.util.List;

public final class TrackMixPlugin extends SpwPlugin {
    private static final System.Logger LOG = System.getLogger(TrackMixPlugin.class.getName());
    private static final String BASS_BINDING = "com.moriafly.bass.BASS";
    // Windows Steam SPW 1.19.3 stream factory used by the local PCM decoder.
    private static final String CREATE_FILE_STREAM = "\u037f";

    private HookHandle hook;
    private BassSourceDsp effects;
    private boolean stopped;

    public TrackMixPlugin(PluginContext context) {
        super(context);
    }

    @Override
    public synchronized void start() {
        var context = getPluginContext();
        if (!"1.19.3".equals(context.getSpwVersion())
                || context.getSpwChannel() != Channel.Steam
                || !System.getProperty("os.name", "").startsWith("Windows")
                || !"amd64".equals(System.getProperty("os.arch"))) {
            LOG.log(System.Logger.Level.INFO, "The PCM probe is limited to SPW Steam 1.19.3 on Windows x64");
            return;
        }

        ProbeSignal.prepare();
        stopped = false;
        hook = WorkshopApi.hookRegistrar().hookMethod(BASS_BINDING, CREATE_FILE_STREAM,
                List.of("java.lang.String", "long", "long", "int"), new MethodHook() {
                    @Override
                    public void after(MethodHookParam call) {
                        if (!call.isOriginalSkipped() && call.getThrowable() == null
                                && call.getResult() instanceof Integer channel && channel != 0
                                && call.getArgs()[0] instanceof String path
                                && isProbePath(path)) {
                            attachProbe(channel);
                        }
                    }
                });
    }

    private static boolean isProbePath(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separator + 1).equalsIgnoreCase(ProbeSignal.FILE_NAME);
    }

    private synchronized void attachProbe(int channel) {
        if (stopped) {
            return;
        }
        try {
            if (effects == null) {
                String resources = System.getProperty("compose.application.resources.dir");
                var bass = BassLibrary.resolveLoaded(Path.of(resources).toAbsolutePath().resolve("bass.dll"));
                effects = new BassSourceDsp(bass);
            }
            if (effects.attach(channel)) {
                LOG.log(System.Logger.Level.INFO, "Attached the six-tone DSP to the SPW test track");
            }
        } catch (Throwable error) {
            LOG.log(System.Logger.Level.WARNING, "The SPW test track will play through unmodified", error);
        }
    }

    @Override
    public void stop() {
        HookHandle handle;
        BassSourceDsp activeEffects;
        synchronized (this) {
            stopped = true;
            handle = hook;
            hook = null;
            activeEffects = effects;
            effects = null;
        }

        try {
            if (activeEffects != null) {
                activeEffects.close();
            }
        } finally {
            if (handle != null) {
                handle.unhook();
            }
        }
    }
}
