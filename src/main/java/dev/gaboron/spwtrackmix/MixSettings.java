package dev.gaboron.spwtrackmix;

import java.util.Arrays;

final class MixSettings {
    enum Preset { ORIGINAL, INSTRUMENTAL, VOCALS, CUSTOM }

    static final int STEMS = 6;
    private static final int ALL = (1 << STEMS) - 1;
    private static final int VOCAL = 1;
    private static final int[] DEFAULT_LEVELS = {100, 100, 100, 100, 100, 100};
    static final MixSettings DEFAULT = new MixSettings(Preset.ORIGINAL, DEFAULT_LEVELS, 0, 0);

    final Preset preset;
    private final int[] volumes;
    private final int muted;
    private final int solos;

    MixSettings(Preset preset, int[] volumes, int muted, int solos) {
        if (volumes.length != STEMS || Arrays.stream(volumes).anyMatch(volume -> volume < 1 || volume > 150)) {
            throw new IllegalArgumentException("Unknown stem levels");
        }
        this.preset = preset;
        this.volumes = volumes.clone();
        this.muted = muted & ALL;
        this.solos = solos & ALL;
    }

    int effectiveVolume(int lane) {
        int bit = 1 << lane;
        int solo = soloMask();
        if ((muteMask() & bit) != 0 || (solo != 0 && (solo & bit) == 0)) {
            return 0;
        }
        return preset == Preset.CUSTOM ? volumes[lane] : 100;
    }

    int muteMask() {
        return switch (preset) {
            case INSTRUMENTAL -> VOCAL;
            case CUSTOM -> muted;
            default -> 0;
        };
    }

    int soloMask() {
        return switch (preset) {
            case VOCALS -> VOCAL;
            case CUSTOM -> solos;
            default -> 0;
        };
    }

    MixSettings withVolume(int lane, int value) {
        if (value == effectiveVolume(lane)) {
            return this;
        }
        var custom = editPreset();
        int bit = 1 << lane;
        if (value == 0) {
            return new MixSettings(Preset.CUSTOM, custom.volumes, custom.muted | bit, custom.solos);
        }
        int[] updated = custom.volumes.clone();
        updated[lane] = value;
        int audibleSolos = custom.solos == 0 ? 0 : custom.solos | bit;
        return new MixSettings(Preset.CUSTOM, updated, custom.muted & ~bit, audibleSolos);
    }

    MixSettings withMuted(int lane) {
        var custom = editPreset();
        return new MixSettings(Preset.CUSTOM, custom.volumes, custom.muted ^ (1 << lane), custom.solos);
    }

    MixSettings withSolo(int lane) {
        var custom = editPreset();
        return new MixSettings(Preset.CUSTOM, custom.volumes, custom.muted, custom.solos ^ (1 << lane));
    }

    MixSettings withPreset(Preset value) {
        return value == preset ? this : new MixSettings(value, volumes, muted, solos);
    }

    private MixSettings editPreset() {
        // Start edits from the controls visible in the selected preset.
        // The explicit Custom button can still recall the last custom mix.
        return preset == Preset.CUSTOM ? this : new MixSettings(Preset.CUSTOM, DEFAULT_LEVELS, muteMask(), soloMask());
    }

    float gain(int lane) {
        return preset == Preset.ORIGINAL ? 0 : effectiveVolume(lane) / 100f;
    }

    boolean original() {
        return preset == Preset.ORIGINAL || (preset == Preset.CUSTOM && muted == 0 && solos == 0
                && Arrays.equals(volumes, DEFAULT_LEVELS));
    }
}
