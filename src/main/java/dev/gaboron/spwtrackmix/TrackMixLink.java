package dev.gaboron.spwtrackmix;

import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.Map;

public final class TrackMixLink implements AutoCloseable {
    private static final ObjectName MIXER = name("dev.gaboron.spwtrackmix:type=Mixer,version=1");
    private static final ObjectName AMLL_PAGE = name("io.github.gaboron.amllspw:type=RebuiltPlayer,version=2");
    private static final ObjectName LEGACY_PAGE = name("io.github.gaboron.amllspw:type=RebuiltPlayer,version=1");
    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private final AmllPlayerRegistration extensions;
    private boolean closed;

    public interface Mixer {
        Map<String, Object> getState();
        void command(String action, int lane, int value);
        void configure(int cacheGiB, boolean quality, boolean cpu, boolean fp16);
        Object[] spectrum(String path, long positionMs);
    }

    TrackMixLink(TrackMixSession session) {
        try {
            server.registerMBean(new StandardMBean(new Controls(session), Mixer.class, false), MIXER);
            try {
                extensions = new AmllPlayerRegistration(server, session);
            } catch (JMException | RuntimeException error) {
                try {
                    server.unregisterMBean(MIXER);
                } catch (JMException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        } catch (JMException error) {
            throw new IllegalStateException("无法注册六轨联动接口", error);
        }
    }

    static boolean amllRebuildsPlayer() {
        try {
            var server = ManagementFactory.getPlatformMBeanServer();
            var endpoint = server.isRegistered(AMLL_PAGE) ? AMLL_PAGE : LEGACY_PAGE;
            return Boolean.TRUE.equals(server.getAttribute(endpoint, "Active"));
        } catch (JMException unavailable) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            try {
                extensions.close();
            } finally {
                server.unregisterMBean(MIXER);
            }
        } catch (InstanceNotFoundException ignored) {
            // A stopped plugin may be closed again during an update.
        } catch (JMException error) {
            throw new IllegalStateException("无法注销六轨联动接口", error);
        }
    }

    private static ObjectName name(String text) {
        try {
            return new ObjectName(text);
        } catch (JMException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record Controls(TrackMixSession session) implements Mixer {
        @Override
        public Map<String, Object> getState() {
            var state = session.status();
            var selection = state.selection();
            var mix = state.mix();
            var options = session.options();
            var jobs = state.queue().items();
            var first = jobs.isEmpty() ? null : jobs.getFirst();
            var tasks = jobs.stream().map(job -> Map.<String, Object>of(
                    "id", job.id(),
                    "title", job.title(),
                    "kind", job.kind().name(),
                    "running", job.running(),
                    "cancelling", job.cancelling(),
                    "stage", job.progress().stage(),
                    "progress", job.progress().fraction())).toList();
            boolean cached = selection != null && selection.entry() != null;
            var levels = new int[MixSettings.STEMS];
            for (int lane = 0; lane < levels.length; lane++) {
                levels[lane] = mix.effectiveVolume(lane);
            }
            return Map.ofEntries(
                    Map.entry("title", selection == null ? "" : selection.track().title()),
                    Map.entry("trackId", selection == null ? "" : selection.track().id()),
                    Map.entry("trackPath", selection == null ? "" : selection.track().path().toString()),
                    Map.entry("cached", cached),
                    Map.entry("ready", state.ready()),
                    Map.entry("canSeparate", state.canSeparate()),
                    Map.entry("canQuick", state.canQuickSeparate()),
                    Map.entry("currentQueued", state.queue().currentQueued()),
                    Map.entry("quickCached", cached && selection.entry().quick()),
                    Map.entry("levels", levels),
                    Map.entry("muted", mix.muteMask()),
                    Map.entry("solos", mix.soloMask()),
                    Map.entry("preset", mix.preset.ordinal()),
                    Map.entry("original", mix.original()),
                    Map.entry("busy", first != null),
                    Map.entry("stage", first == null ? "" : first.progress().stage()),
                    Map.entry("progress", first == null ? 0d : first.progress().fraction()),
                    Map.entry("jobs", tasks),
                    Map.entry("jobNotice", state.queue().notice()),
                    Map.entry("settings", Map.of("cacheGiB", options.cacheGiB(), "quality", options.quality(),
                            "cpu", options.cpu(), "fp16", options.fp16(), "modelReady", state.modelReady(),
                            "cacheBytes", state.cacheBytes(), "hasTrack", selection != null && selection.identity() != null)),
                    Map.entry("notice", selection != null && !selection.error().isBlank() ? selection.error() : state.notice()));
        }

        @Override
        public void command(String action, int lane, int value) {
            switch (action) {
                case "separate" -> session.separate(false);
                case "quick" -> session.separate(true);
                case "prepareModel" -> session.prepareModel();
                case "removeModel" -> session.removeModel();
                case "clearCurrent" -> session.clearCurrent();
                case "clearAll" -> session.clearAll();
                case "cancel" -> session.cancel(value);
                case "reset" -> session.setMix(ignored -> MixSettings.DEFAULT);
                case "preset" -> {
                    var presets = MixSettings.Preset.values();
                    if (value >= 0 && value < presets.length) {
                        session.setMix(current -> current.withPreset(presets[value]));
                    }
                }
                case "level", "mute", "solo" -> {
                    if (lane < 0 || lane >= MixSettings.STEMS) {
                        return;
                    }
                    switch (action) {
                        case "level" -> session.setMix(current -> current.withVolume(lane, Math.clamp(value, 0, 150)));
                        case "mute" -> session.setMix(current -> current.withMuted(lane));
                        case "solo" -> session.setMix(current -> current.withSolo(lane));
                    }
                }
                default -> {}
            }
        }

        @Override
        public void configure(int cacheGiB, boolean quality, boolean cpu, boolean fp16) {
            if (cacheGiB >= 1 && cacheGiB <= 100) {
                session.setOptions(cacheGiB, quality, cpu, fp16);
            }
        }

        @Override
        public Object[] spectrum(String path, long positionMs) {
            return session.spectrum(path, positionMs);
        }
    }
}
