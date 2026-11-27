package dev.gaboron.spwtrackmix;

import java.nio.file.Path;
import java.util.regex.Pattern;

record CacheFileName(String prefix, String model, boolean quick, String digest) {
    static final String CURRENT_MODEL = "a443a298";
    private static final Pattern NAME = Pattern.compile(
            "(?:(.+) \\[)?([a-f0-9]{8})-(quick-)?([a-f0-9]{64})(\\])?\\.jema");

    static CacheFileName parse(Path path) {
        var match = NAME.matcher(path.getFileName().toString());
        if (!match.matches() || (match.group(1) == null) != (match.group(5) == null)) return null;
        return new CacheFileName(match.group(1), match.group(2), match.group(3) != null,
                match.group(4));
    }

    String key() {
        return model + "-" + (quick ? "quick-" : "") + digest;
    }

    String forTitle(String title) {
        String safeTitle = title.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}<>:\"/\\\\|?*]", " ").strip();
        if (safeTitle.isBlank()) return key() + ".jema";
        if (safeTitle.codePointCount(0, safeTitle.length()) > 64) {
            safeTitle = safeTitle.substring(0, safeTitle.offsetByCodePoints(0, 64)).stripTrailing();
        }
        return safeTitle + suffix() + " [" + key() + "].jema";
    }

    String title() {
        String suffix = suffix();
        return prefix != null && prefix.endsWith(suffix) ? prefix.substring(0, prefix.length() - suffix.length()) : "";
    }

    private String suffix() {
        return quick ? " - 快速分离" : " - 标准分离";
    }
}
