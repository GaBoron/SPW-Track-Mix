package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TrackMixPanel implements AmllPlayerRegistration.Panel {
    private static final List<String> TRACKS = List.of("人声", "鼓组", "贝斯", "吉他", "钢琴", "其他");
    private static final List<String> PRESETS = List.of("原曲", "纯伴奏", "仅人声", "自定义");
    private static final Map<String, Object> DESCRIPTOR = Map.of(
            "label", "六轨混音", "svg", loadIcon(), "width", 340, "keepOnTrackChange", true);

    private final TrackMixSession session;
    private final Map<String, SettingsDraft> drafts = new HashMap<>();

    TrackMixPanel(TrackMixSession session) {
        this.session = session;
    }

    @Override
    public Map<String, Object> getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public synchronized List<Map<String, Object>> render(String token, Map<String, String> context) {
        var status = session.status();
        var path = localPath(context.get("path"));
        var track = selectedTrack(status, context.get("trackId"), path);
        var controls = new ArrayList<Map<String, Object>>();
        var draft = drafts.get(token);
        if (draft != null) {
            settings(controls, draft, status, track);
        } else {
            mixer(controls, status, track, path != null);
        }
        queue(controls, status.queue());
        if (draft == null && track != null && track.entry() != null && status.attached()) {
            faders(controls, status.mix());
        }
        return controls;
    }

    private static void mixer(List<Map<String, Object>> controls, TrackMixSession.Status status,
                              TrackMixSession.Selection track, boolean local) {
        var summary = new ArrayList<Map<String, Object>>();
        summary.add(node("text", "title", "text", "六轨混音", "size", 17, "emphasis", true));
        if (track != null) {
            summary.add(text("song", track.track().title(), true));
        }
        String description;
        if (!local) {
            description = "请播放本地立体声歌曲";
        } else if (track == null) {
            description = status.selection() == null ? "正在读取曲目" : "正在切换曲目";
        } else if (!track.error().isBlank()) {
            description = track.error();
        } else if (!status.notice().isBlank()) {
            description = status.notice();
        } else if (track.entry() != null && status.attached()) {
            String playback = status.mix().original() ? "原曲直通" : "实时混音";
            description = track.entry().quick() ? "快速分离 · " + playback : playback;
        } else if (track.entry() != null) {
            description = "重新打开歌曲以接入混音";
        } else {
            description = status.queue().currentQueued() ? "已加入分离队列" : "这首歌尚未分离";
        }
        summary.add(node("text", "description", "text", description, "muted", true, "lines", 2));
        controls.add(group("column", "summary", 12, 4, summary));
        if (track != null && status.canSeparate()) {
            controls.add(button("separate", "标准分离", true));
        }
        if (track != null && status.canQuickSeparate()) {
            controls.add(button("quick", "快速分离", true));
        }
        controls.add(button("settings", "模型和缓存", true));
    }

    private static void faders(List<Map<String, Object>> controls, MixSettings mix) {
        var presets = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < PRESETS.size(); index++) {
            presets.add(chip("preset." + index, PRESETS.get(index), mix.preset.ordinal() == index, true));
        }
        presets.add(chip("reset", "重置", false, true));
        controls.add(node("divider", "presets-divider"));
        controls.add(group("wrap", "presets", 10, 4, presets));
        controls.add(node("divider", "faders-divider"));
        var lanes = new ArrayList<Map<String, Object>>();
        for (int lane = 0; lane < TRACKS.size(); lane++) {
            int bit = 1 << lane;
            String name = TRACKS.get(lane);
            lanes.add(group("row", "lane." + lane, 12, 5, List.of(
                    node("text", "name", "text", name, "width", 30),
                    node("slider", "volume", "action", "level." + lane, "text", name + " 音量", "value",
                            mix.effectiveVolume(lane), "max", 150, "unit", "%", "fill", true,
                            "continuous", true, "valueWidth", 39),
                    chip("mute." + lane, "静音", (mix.muteMask() & bit) != 0, true),
                    chip("solo." + lane, "独奏", (mix.soloMask() & bit) != 0, true))));
        }
        controls.add(group("column", "faders", 0, 2, lanes));
    }

    private static void queue(List<Map<String, Object>> controls, TrackMixJobs.Snapshot queue) {
        var items = queue.items();
        if (!items.isEmpty()) {
            var rows = new ArrayList<Map<String, Object>>();
            rows.add(node("text", "heading", "text", "后台任务", "size", 14, "emphasis", true));
            for (int index = 0; index < items.size(); index++) {
                var job = items.get(index);
                String kind = switch (job.kind()) {
                    case QUICK -> "快速分离";
                    case STANDARD -> "标准分离";
                    case MAINTENANCE -> "模型和缓存";
                };
                String progress = job.cancelling() ? "正在取消"
                        : job.running() ? job.progress().label() : "排队 · 第 " + index + " 位";
                rows.add(group("row", "job." + job.id(), 0, 6, List.of(
                        node("column", "details", "fill", true, "gap", 3, "children", List.of(
                                text("name", job.title(), false),
                                node("text", "progress-label", "text", kind + " · " + progress, "muted", true, "size", 12))),
                        chip("cancel." + job.id(), "取消", false, !job.cancelling()))));
                rows.add(node("progress", "progress." + job.id(), "value",
                        job.running() ? job.progress().fraction() : 0d));
            }
            controls.add(node("divider", "queue-divider"));
            controls.add(group("column", "queue", 12, 6, rows));
        }
        if (!queue.notice().isBlank()) {
            controls.add(node("text", "job-notice", "text", queue.notice(), "muted", true,
                    "size", 12, "lines", 3, "padding", 12));
        }
    }

    private static void settings(List<Map<String, Object>> controls, SettingsDraft draft, TrackMixSession.Status status,
                                 TrackMixSession.Selection track) {
        if (draft.confirm.equals("clearCurrent") && (track == null || !track.track().sameSource(draft.confirmTrack))) {
            draft.confirm = "";
        }
        var options = draft.options;
        controls.add(button("back", "返回调音台", true));
        controls.add(group("column", "settings-content", 12, 5, List.of(
                node("text", "title", "text", "模型和缓存", "emphasis", true, "size", 17),
                text("model", status.modelReady() ? "BS-RoFormer SW Fixed 已下载" : "首次分离时下载模型", true),
                text("cache", "已用缓存 %.2f GiB".formatted(status.cacheBytes() / 1073741824.0), true),
                node("slider", "limit", "text", "缓存上限", "action", "cacheGiB", "value", options.cacheGiB(),
                        "min", 1, "max", 100, "unit", " GiB", "valueWidth", 62),
                group("wrap", "options", 0, 5, List.of(
                        chip("quality", "高质量 TTA", options.quality(), true),
                        chip("fp16", "CUDA FP16", options.fp16() && !options.cpu(), !options.cpu()),
                        chip("cpu", "仅 CPU", options.cpu(), true))))));
        controls.add(button("save", "保存设置", true));
        controls.add(node("divider", "maintenance-divider"));
        boolean idle = status.queue().items().isEmpty();
        controls.add(button("prepareModel", "准备模型", idle));
        controls.add(button("clearCurrent", "清除当前歌曲缓存", idle && track != null && track.identity() != null));
        controls.add(button("clearAll", "清除全部缓存", idle && status.cacheBytes() > 0));
        controls.add(button("removeModel", "清除模型", idle));
        if (!draft.confirm.isBlank()) {
            String target = switch (draft.confirm) {
                case "clearCurrent" -> "当前歌曲的音轨缓存";
                case "clearAll" -> "全部音轨缓存";
                default -> "模型文件";
            };
            controls.add(group("column", "confirmation", 12, 5, List.of(
                    text("question", "将" + target + "移入回收站？", false),
                    group("wrap", "confirm-actions", 0, 5,
                            List.of(chip("confirm", "确认", false, idle), chip("reject", "返回", false, true))))));
        }
    }

    @Override
    public synchronized void action(String token, Map<String, String> context, String action, int value) {
        var status = session.status();
        var track = selectedTrack(status, context.get("trackId"), localPath(context.get("path")));
        var draft = drafts.get(token);
        if (action.startsWith("cancel.")) {
            int id = suffix(action);
            if (id > 0) session.cancel(id);
            return;
        }
        if (draft != null) {
            editSettings(token, draft, status, track, action, value);
            return;
        }
        if (action.equals("settings")) {
            drafts.put(token, new SettingsDraft(session.options()));
            return;
        }
        if (track == null) return;
        if (action.equals("separate") && status.canSeparate()) {
            session.separate(track.track(), false);
        } else if (action.equals("quick") && status.canQuickSeparate()) {
            session.separate(track.track(), true);
        } else if (track.entry() != null && status.attached()) {
            editFaders(track.track(), action, value);
        }
    }

    private void editFaders(TrackMixSession.Track track, String action, int value) {
        if (action.equals("reset")) {
            session.setMix(track, ignored -> MixSettings.DEFAULT);
            return;
        }
        int index = suffix(action);
        if (index < 0 || index >= MixSettings.STEMS) return;
        switch (action.substring(0, action.lastIndexOf('.'))) {
            case "level" -> session.setMix(track, mix -> mix.withVolume(index, Math.clamp(value, 0, 150)));
            case "mute" -> session.setMix(track, mix -> mix.withMuted(index));
            case "solo" -> session.setMix(track, mix -> mix.withSolo(index));
            case "preset" -> {
                var presets = MixSettings.Preset.values();
                if (index < presets.length) session.setMix(track, mix -> mix.withPreset(presets[index]));
            }
        }
    }

    private void editSettings(String token, SettingsDraft draft, TrackMixSession.Status status,
                              TrackMixSession.Selection track, String action, int value) {
        var current = draft.options;
        boolean idle = status.queue().items().isEmpty();
        switch (action) {
            case "back" -> drafts.remove(token);
            case "save" -> {
                session.setOptions(current.cacheGiB(), current.quality(), current.cpu(), current.fp16());
                drafts.remove(token);
            }
            case "cacheGiB" -> draft.options = new TrackMixOptions(Math.clamp(value, 1, 100),
                    current.quality(), current.cpu(), current.fp16());
            case "quality" -> draft.options = new TrackMixOptions(current.cacheGiB(),
                    !current.quality(), current.cpu(), current.fp16());
            case "cpu" -> draft.options = new TrackMixOptions(current.cacheGiB(),
                    current.quality(), !current.cpu(), current.fp16());
            case "fp16" -> {
                if (!current.cpu()) draft.options = new TrackMixOptions(current.cacheGiB(),
                        current.quality(), false, !current.fp16());
            }
            case "prepareModel" -> {
                if (idle) session.prepareModel();
            }
            case "clearCurrent" -> {
                if (idle && track != null && track.identity() != null) {
                    draft.confirm = action;
                    draft.confirmTrack = track.track();
                }
            }
            case "clearAll", "removeModel" -> {
                if (idle) draft.confirm = action;
            }
            case "reject" -> draft.confirm = "";
            case "confirm" -> {
                if (!idle) return;
                switch (draft.confirm) {
                    case "clearCurrent" -> {
                        if (track != null && track.track().sameSource(draft.confirmTrack)) session.clearCurrent(track.track());
                    }
                    case "clearAll" -> session.clearAll();
                    case "removeModel" -> session.removeModel();
                }
                draft.confirm = "";
            }
        }
    }

    @Override
    public synchronized void close(String token) {
        drafts.remove(token);
    }

    private static TrackMixSession.Selection selectedTrack(TrackMixSession.Status status, String id, Path path) {
        var selected = status.selection();
        return selected != null && selected.track().path().equals(path)
                && selected.track().id().equals(id) ? selected : null;
    }

    private static Path localPath(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            var local = Path.of(path);
            return local.isAbsolute() ? local.normalize() : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private static int suffix(String command) {
        int separator = command.lastIndexOf('.');
        if (separator < 0) return -1;
        try {
            return Integer.parseInt(command.substring(separator + 1));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static Map<String, Object> button(String action, String text, boolean enabled) {
        return node("button", action, "action", action, "text", text, "enabled", enabled);
    }

    private static Map<String, Object> chip(String action, String text, boolean selected, boolean enabled) {
        return node("button", action, "action", action, "text", text, "style", "chip",
                "selected", selected, "enabled", enabled);
    }

    private static Map<String, Object> text(String id, String text, boolean muted) {
        return node("text", id, "text", text, "muted", muted);
    }

    private static Map<String, Object> group(String type, String id, int padding, int gap,
                                             List<Map<String, Object>> children) {
        return node(type, id, "padding", padding, "gap", gap, "children", children);
    }

    private static Map<String, Object> node(String type, String id, Object... properties) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("id", id);
        for (int index = 0; index < properties.length; index += 2) {
            result.put((String) properties[index], properties[index + 1]);
        }
        return Map.copyOf(result);
    }

    private static String loadIcon() {
        try (var icon = TrackMixPanel.class.getResourceAsStream("/trackmix/icon_stems.svg")) {
            return icon == null ? "" : new String(icon.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static final class SettingsDraft {
        private TrackMixOptions options;
        private String confirm = "";
        private TrackMixSession.Track confirmTrack;

        SettingsDraft(TrackMixOptions options) {
            this.options = options;
        }
    }
}
