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
    private static final ObjectName AMLL_PAGE = name("io.github.gaboron.amllspw:type=RebuiltPlayer,version=1");
    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    public interface Mixer {
        Map<String, Object> getState();
        void command(String action, int lane, int value);
        Object[] spectrum(String path, long positionMs);
    }

    TrackMixLink(TrackMixSession session) {
        try {
            server.registerMBean(new StandardMBean(new Controls(session), Mixer.class, false), MIXER);
        } catch (JMException error) {
            throw new IllegalStateException("无法注册六轨联动接口", error);
        }
    }

    static boolean amllRebuildsPlayer() {
        try {
            return Boolean.TRUE.equals(ManagementFactory.getPlatformMBeanServer().getAttribute(AMLL_PAGE, "Active"));
        } catch (JMException unavailable) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            server.unregisterMBean(MIXER);
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
            var job = state.job();
            boolean cached = selection != null && selection.entry() != null;
            var levels = new int[MixSettings.STEMS];
            for (int lane = 0; lane < levels.length; lane++) {
                levels[lane] = mix.volume(lane);
            }
            return Map.ofEntries(
                    Map.entry("title", selection == null ? "" : selection.track().title()),
                    Map.entry("cached", cached),
                    Map.entry("ready", cached && state.attached()),
                    Map.entry("canSeparate", job == null && !cached && selection != null && selection.identity() != null),
                    Map.entry("levels", levels),
                    Map.entry("muted", mix.muted),
                    Map.entry("solos", mix.solos),
                    Map.entry("preset", mix.preset.ordinal()),
                    Map.entry("original", mix.original()),
                    Map.entry("busy", job != null),
                    Map.entry("stage", job == null ? "" : job.stage),
                    Map.entry("progress", job == null ? 0d : job.progress),
                    Map.entry("notice", selection != null && !selection.error().isBlank() ? selection.error() : state.notice()));
        }

        @Override
        public void command(String action, int lane, int value) {
            switch (action) {
                case "separate" -> session.separate();
                case "cancel" -> session.cancel();
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
        public Object[] spectrum(String path, long positionMs) {
            return session.spectrum(path, positionMs);
        }
    }
}
