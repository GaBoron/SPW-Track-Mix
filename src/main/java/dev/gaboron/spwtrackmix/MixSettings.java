package dev.gaboron.spwtrackmix;

import java.util.Arrays;

final class MixSettings {
    enum Preset { ORIGINAL, INSTRUMENTAL, VOCALS, CUSTOM }

    static final int STEMS = 6;
    private static final int ALL = (1 << STEMS) - 1;
    static final MixSettings DEFAULT = new MixSettings(Preset.ORIGINAL,
            new int[]{100, 100, 100, 100, 100, 100}, 0, 0);

    final Preset preset;
    private final int[] volumes;
    final int muted;
    final int solos;

    MixSettings(Preset preset, int[] volumes, int muted, int solos) {
        if (volumes.length != STEMS || Arrays.stream(volumes).anyMatch(volume -> volume < 0 || volume > 150)) {
            throw new IllegalArgumentException("Unknown stem levels");
        }
        this.preset = preset;
        this.volumes = volumes.clone();
        this.muted = muted & ALL;
        this.solos = solos & ALL;
    }

    int volume(int lane) {
        return volumes[lane];
    }

    MixSettings withVolume(int lane, int value) {
        int[] updated = volumes.clone();
        updated[lane] = value;
        return new MixSettings(Preset.CUSTOM, updated, muted, solos);
    }

    MixSettings withMuted(int lane) {
        return new MixSettings(Preset.CUSTOM, volumes, muted ^ (1 << lane), solos);
    }

    MixSettings withSolo(int lane) {
        return new MixSettings(Preset.CUSTOM, volumes, muted, solos ^ (1 << lane));
    }

    MixSettings withPreset(Preset value) {
        return new MixSettings(value, volumes, muted, solos);
    }

    float gain(int lane) {
        int bit = 1 << lane;
        return switch (preset) {
            case ORIGINAL -> 0;
            case INSTRUMENTAL -> lane == 0 ? 0 : 1;
            case VOCALS -> lane == 0 ? 1 : 0;
            case CUSTOM -> (muted & bit) != 0 || (solos != 0 && (solos & bit) == 0)
                    ? 0 : volumes[lane] / 100f;
        };
    }

    boolean original() {
        return preset == Preset.ORIGINAL || (preset == Preset.CUSTOM && muted == 0 && solos == 0
                && volumes[0] == 100 && volumes[1] == 100 && volumes[2] == 100
                && volumes[3] == 100 && volumes[4] == 100 && volumes[5] == 100);
    }
}
