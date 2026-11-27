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
import java.util.Locale;
import java.util.Map;

final class TrackMixPanel implements AmllPlayerRegistration.Panel {
    private static final int CACHE_PAGE_SIZE = 12;
    private static final List<String> TRACKS = List.of("人声", "鼓组", "贝斯", "吉他", "钢琴", "其他");
    private static final List<String> PRESETS = List.of("原曲", "纯伴奏", "仅人声", "自定义");
    private static final Map<String, Object> DESCRIPTOR = Map.of(
            "label", "六轨混音", "svg", loadIcon(), "width", 340, "keepOnTrackChange", true);
    private static final String SETTINGS_ICON = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="1.7" stroke-linejoin="round">
              <path d="M10 3h4l.6 2.6 1.2.5 2.3-1.4 2.8 2.8-1.4 2.3.5 1.2 2.6.6v4l-2.6.6-.5 1.2
                       1.4 2.3-2.8 2.8-2.3-1.4-1.2.5-.6 2.6h-4l-.6-2.6-1.2-.5-2.3 1.4-2.8-2.8
                       1.4-2.3-.5-1.2-2.6-.6v-4l2.6-.6.5-1.2-1.4-2.3 2.8-2.8 2.3 1.4 1.2-.5z"
                    transform="translate(1.4 .15) scale(.88)"/>
              <circle cx="12" cy="12.1" r="3"/>
            </svg>
            """;

    private final TrackMixSession session;
    private final CacheDirectoryActions cacheDirectories;
    private final Map<String, PanelView> views = new HashMap<>();

    TrackMixPanel(TrackMixSession session, CacheDirectoryActions cacheDirectories) {
        this.session = session;
        this.cacheDirectories = cacheDirectories;
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
        var view = views.computeIfAbsent(token, ignored -> new PanelView(status.queue().noticeId()));
        var draft = view.settings;
        if (draft == null) {
            mixer(controls, status, track, path != null);
        } else if (draft.manageCache) {
            manageCache(controls, draft, status, session.cacheCatalog());
        } else {
            settings(controls, draft, status);
        }
        queue(controls, status.queue(), view.openedAfter);
        if (draft == null && track != null && status.ready()) {
            faders(controls, status.mix());
        }
        return controls;
    }

    private static void mixer(List<Map<String, Object>> controls, TrackMixSession.Status status,
                              TrackMixSession.Selection track, boolean local) {
        var summary = new ArrayList<Map<String, Object>>();
        summary.add(group("row", "heading", 0, 4, List.of(
                node("text", "title", "text", "六轨混音", "size", 17, "emphasis", true, "fill", true),
                node("button", "settings", "action", "settings", "text", "设置", "style", "chip",
                        "svg", SETTINGS_ICON))));
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
        } else if (status.ready()) {
            String playback = status.mix().original() ? "原曲直通" : "实时混音";
            description = track.entry().quick() ? "快速分离 · " + playback : playback;
        } else if (track.entry() != null) {
            description = "六轨已缓存 · 当前播放暂未接入混音";
        } else {
            description = status.queue().currentQueued() ? "已加入分离队列" : "这首歌尚未分离";
        }
        summary.add(node("text", "description", "text", description, "muted", true, "lines", 2));
        controls.add(node("column", "summary", "scrollKey", "mixer", "padding", 12, "gap", 4, "children", summary));
        if (track != null && status.canSeparate()) {
            controls.add(button("separate", "标准分离", true));
        }
        if (track != null && status.canQuickSeparate()) {
            controls.add(button("quick", "快速分离", true));
        }
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

    private static void queue(List<Map<String, Object>> controls, TrackMixJobs.Snapshot queue, int openedAfter) {
        var items = queue.items();
        if (!items.isEmpty()) {
            var rows = new ArrayList<Map<String, Object>>();
            rows.add(node("text", "heading", "text", "后台任务", "size", 14, "emphasis", true));
            for (int index = 0; index < items.size(); index++) {
                var job = items.get(index);
                String kind = switch (job.kind()) {
                    case QUICK -> "快速分离";
                    case STANDARD -> "标准分离";
                    case MAINTENANCE -> "维护";
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
        String notice = queue.noticeAfter(openedAfter);
        if (!notice.isBlank()) {
            controls.add(node("text", "job-notice", "text", notice, "muted", true,
                    "size", 12, "lines", 3, "padding", 12));
        }
    }

    private void settings(List<Map<String, Object>> controls, SettingsDraft draft, TrackMixSession.Status status) {
        var options = draft.options;
        controls.add(pageHeading("settings", "混音设置", "back", "返回调音台"));
        boolean idle = status.queue().items().isEmpty();
        boolean modelPresent = status.modelBytes() > 0;
        String modelState = status.modelReady() ? "已下载" : modelPresent ? "未就绪" : "首次分离时下载";
        if (modelPresent) modelState += " · " + formatSize(status.modelBytes());
        var model = new ArrayList<Map<String, Object>>();
        var details = List.of(
                node("text", "model-name", "text", "BS-RoFormer SW Fixed", "emphasis", true),
                text("model-state", modelState, true));
        var actions = new ArrayList<Map<String, Object>>();
        actions.add(node("column", "model-details", "fill", true, "gap", 3, "children", details));
        if (!status.modelReady()) actions.add(chip("prepareModel", "下载", false, idle));
        if (modelPresent) actions.add(chip("removeModel", "删除", false, idle && !draft.confirmModel));
        model.add(group("row", "model", 0, 6, actions));
        if (draft.confirmModel) {
            model.add(confirmation("将模型文件移入回收站？", "confirmModel", "cancelModel", idle));
        }
        controls.add(group("column", "model-card", 12, 8, model));
        controls.add(node("divider", "settings-divider"));
        controls.add(group("column", "settings-content", 12, 8, List.of(
                node("text", "cache-directory", "text", "缓存目录", "emphasis", true),
                node("text", "cache-path", "text", session.cacheDirectory().toString(), "muted", true, "lines", 2),
                group("wrap", "directory-actions", 0, 8, List.of(
                        chip("chooseCacheDirectory", "选择目录", false, idle),
                        chip("resetCacheDirectory", "恢复默认", false, idle && session.options().cacheParent() != null))),
                node("text", "cache-limit", "text", "缓存上限", "emphasis", true),
                node("slider", "limit", "text", "缓存上限", "action", "cacheGiB", "value", options.cacheGiB(),
                        "min", 1, "max", 100, "unit", " GiB", "valueWidth", 62),
                group("wrap", "options", 0, 5, List.of(
                        chip("quality", "高质量 TTA", options.quality(), true),
                        chip("fp16", "CUDA FP16", options.fp16() && !options.cpu(), !options.cpu()),
                        chip("cpu", "仅 CPU", options.cpu(), true))))));
        controls.add(button("manageCache", "管理缓存", true));
        controls.add(button("save", "保存设置", true));
    }

    private static void manageCache(List<Map<String, Object>> controls, SettingsDraft draft, TrackMixSession.Status status,
                                    TrackMixSession.CacheCatalog catalog) {
        var tracks = catalog == null ? List.<StemCache.CachedTrack>of() : catalog.tracks();
        int lastPage = Math.max(0, (tracks.size() - 1) / CACHE_PAGE_SIZE);
        draft.cachePage = Math.clamp(draft.cachePage, 0, lastPage);
        boolean idle = status.queue().items().isEmpty();
        boolean confirmAll = "".equals(draft.removeDigest);
        String total = catalog == null ? "正在读取缓存" : !catalog.error().isBlank() ? catalog.error()
                : tracks.isEmpty() ? "暂无缓存，已占用 0 B" : tracks.size() + " 首 · 已占用 " + formatSize(catalog.bytes());
        controls.add(node("column", "heading", "scrollKey", "cache-" + draft.cachePage,
                "padding", 12, "gap", 8, "children", List.of(
                        group("row", "cache-actions", 0, 8, List.of(
                                node("text", "title", "text", "管理缓存", "size", 17, "emphasis", true, "fill", true),
                                chip("backFromCache", "返回设置", false, true),
                                chip("deleteAll", "清空", false, idle && !confirmAll && !tracks.isEmpty()
                                        && catalog.error().isBlank()))),
                        text("total", total, true))));
        if (catalog == null || !catalog.error().isBlank() || tracks.isEmpty()) {
            return;
        }
        if (confirmAll) {
            controls.add(group("column", "clear-all", 12, 4,
                    List.of(confirmation("将全部歌曲的音轨缓存移入回收站？", "confirmCache", "cancelCache", idle))));
        }
        controls.add(node("divider", "cache-divider"));

        int first = draft.cachePage * CACHE_PAGE_SIZE;
        int end = Math.min(first + CACHE_PAGE_SIZE, tracks.size());
        for (var track : tracks.subList(first, end)) {
            String quality = track.quick() && track.standard() ? "标准 + 快速"
                    : track.standard() ? "标准分离" : "快速分离";
            boolean confirming = track.digest().equals(draft.removeDigest);
            var row = group("row", "track", 0, 6, List.of(
                    node("column", "details", "fill", true, "gap", 3, "children", List.of(
                            node("text", "name", "text", track.displayName(), "lines", 2, "emphasis", true),
                            text("size", quality + " · " + formatSize(track.bytes()), true))),
                    chip("delete." + track.digest(), "删除", false, idle && !confirming)));
            var children = new ArrayList<Map<String, Object>>();
            children.add(row);
            if (confirming) {
                children.add(confirmation("将这首歌的音轨缓存移入回收站？", "confirmCache", "cancelCache", idle));
            }
            controls.add(group("column", "cached-" + track.digest(), 12, 5, children));
        }
        if (lastPage > 0) {
            controls.add(group("row", "cache-pages", 12, 6, List.of(
                    node("text", "range", "text", (first + 1) + "–" + end + " / " + tracks.size(), "fill", true, "muted", true),
                    chip("previousPage", "上一页", false, draft.cachePage > 0),
                    chip("nextPage", "下一页", false, draft.cachePage < lastPage))));
        }
    }

    private static Map<String, Object> pageHeading(String page, String title, String back, String label) {
        return node("row", "heading", "scrollKey", page, "padding", 12, "gap", 6, "children", List.of(
                node("text", "title", "text", title, "size", 17, "emphasis", true, "fill", true),
                chip(back, label, false, true)));
    }

    private static Map<String, Object> confirmation(String question, String confirm, String cancel, boolean enabled) {
        return group("column", "confirmation", 0, 5, List.of(
                node("text", "question", "text", question, "lines", 2),
                group("wrap", "confirm-actions", 0, 5,
                        List.of(chip(confirm, "确认删除", false, enabled), chip(cancel, "取消", false, true)))));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < (1L << 20)) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < (1L << 30)) return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (double) (1L << 30));
    }

    @Override
    public synchronized void action(String token, Map<String, String> context, String action, int value) {
        var status = session.status();
        var track = selectedTrack(status, context.get("trackId"), localPath(context.get("path")));
        var view = views.computeIfAbsent(token, ignored -> new PanelView(status.queue().noticeId()));
        var draft = view.settings;
        if (action.startsWith("cancel.")) {
            int id = suffix(action);
            if (id > 0) session.cancel(id);
            return;
        }
        if (draft != null) {
            if (draft.manageCache) {
                editCache(draft, status, action);
            } else {
                editSettings(view, draft, status, action, value);
            }
            return;
        }
        if (action.equals("settings")) {
            view.settings = new SettingsDraft(session.options());
            return;
        }
        if (track == null) return;
        if (action.equals("separate") && status.canSeparate()) {
            session.separate(track.track(), false);
        } else if (action.equals("quick") && status.canQuickSeparate()) {
            session.separate(track.track(), true);
        } else if (status.ready()) {
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

    private void editSettings(PanelView view, SettingsDraft draft, TrackMixSession.Status status, String action, int value) {
        var current = draft.options;
        boolean idle = status.queue().items().isEmpty();
        switch (action) {
            case "back" -> view.settings = null;
            case "save" -> {
                session.setOptions(current.cacheGiB(), current.quality(), current.cpu(), current.fp16());
                view.settings = null;
            }
            case "chooseCacheDirectory" -> { if (idle) cacheDirectories.choose(); }
            case "resetCacheDirectory" -> { if (idle) cacheDirectories.reset(); }
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
                if (idle && !status.modelReady()) session.prepareModel();
            }
            case "manageCache" -> {
                draft.manageCache = true;
                draft.confirmModel = false;
            }
            case "removeModel" -> {
                if (idle && status.modelBytes() > 0) draft.confirmModel = true;
            }
            case "cancelModel" -> draft.confirmModel = false;
            case "confirmModel" -> {
                if (idle && draft.confirmModel && status.modelBytes() > 0) {
                    session.removeModel();
                    draft.confirmModel = false;
                }
            }
        }
    }

    private void editCache(SettingsDraft draft, TrackMixSession.Status status, String action) {
        boolean idle = status.queue().items().isEmpty();
        var catalog = session.cacheCatalog();
        var tracks = catalog == null || !catalog.error().isBlank() ? List.<StemCache.CachedTrack>of() : catalog.tracks();
        switch (action) {
            case "backFromCache" -> {
                draft.manageCache = false;
                draft.removeDigest = null;
            }
            case "previousPage", "nextPage" -> {
                int lastPage = Math.max(0, (tracks.size() - 1) / CACHE_PAGE_SIZE);
                int step = action.equals("nextPage") ? 1 : -1;
                draft.cachePage = Math.clamp(draft.cachePage + step, 0, lastPage);
                draft.removeDigest = null;
            }
            case "deleteAll" -> {
                if (idle && !tracks.isEmpty()) draft.removeDigest = "";
            }
            case "cancelCache" -> draft.removeDigest = null;
            case "confirmCache" -> {
                if (!idle || draft.removeDigest == null) return;
                if (draft.removeDigest.isEmpty() && !tracks.isEmpty()) {
                    session.clearAll();
                } else {
                    tracks.stream().filter(track -> track.digest().equals(draft.removeDigest)).findFirst()
                            .ifPresent(session::clearCachedTrack);
                }
                draft.removeDigest = null;
            }
            default -> {
                if (idle && action.startsWith("delete.")) {
                    String digest = action.substring("delete.".length());
                    if (tracks.stream().anyMatch(track -> track.digest().equals(digest))) draft.removeDigest = digest;
                }
            }
        }
    }

    @Override
    public synchronized void close(String token) {
        views.remove(token);
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

    private static final class PanelView {
        private final int openedAfter;
        private SettingsDraft settings;

        PanelView(int openedAfter) {
            this.openedAfter = openedAfter;
        }
    }

    private static final class SettingsDraft {
        private TrackMixOptions options;
        private boolean confirmModel;
        private boolean manageCache;
        private int cachePage;
        private String removeDigest;

        SettingsDraft(TrackMixOptions options) {
            this.options = options;
        }
    }
}
