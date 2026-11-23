package dev.gaboron.spwtrackmix;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

final class RealtimeStemMixer {
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final int DRY = MixSettings.STEMS;
    private static final float PEAK_CEILING = 0.995f;
    private final float[] gains = new float[DRY + 1];
    private final float[] targets = new float[DRY + 1];
    private final float[] increments = new float[DRY + 1];
    private final int rampFrames;
    private final float limiterRelease;
    private MixSettings lastSettings;
    private StemCache.Entry lastEntry;
    private long nextFrame = -1;
    private int rampRemaining;
    private float limiter = 1;

    RealtimeStemMixer(int rate) {
        rampFrames = Math.max(1, rate / 100);
        limiterRelease = (float) -Math.expm1(-1.0 / (0.08 * rate));
        gains[DRY] = 1;
    }

    boolean process(MemorySegment pcm, long position, int frames, StemCache.Entry entry,
                    StemCache.Window window, MixSettings settings) {
        if (entry == null) {
            reset(1);
            return false;
        }
        boolean original = settings.original();
        if (window == null || !window.covers(position, frames)) {
            reset(original ? 1 : 0);
            if (!original) {
                // A pending seek has no valid stem frame yet. Keep the selected mix isolated.
                pcm.fill((byte) 0);
            }
            return !original;
        }

        // A jump resumes with the selected sources. The dry crossfade is reserved for
        // an actual mode change on the same continuous timeline.
        if (nextFrame != position || lastEntry != entry) {
            reset(original ? 1 : 0);
        }
        if (settings != lastSettings || entry != lastEntry) {
            applyTarget(settings, entry);
        }
        nextFrame = position + frames;

        float[] samples = window.samples();
        int offset = Math.toIntExact(position - window.start()) * StemCache.FLOATS_PER_FRAME;
        boolean mixed = false;
        for (int frame = 0; frame < frames; frame++) {
            if (rampRemaining > 0) {
                advanceGains();
            } else if (targets[DRY] == 1) {
                break;
            }
            mixed = true;
            float left = 0;
            float right = 0;
            for (int track = 0; track < DRY; track++) {
                int sample = offset + track * 2;
                left += samples[sample] * gains[track];
                right += samples[sample + 1] * gains[track];
            }

            float dry = gains[DRY];
            float ceiling = PEAK_CEILING * (1 - dry);
            float peak = Math.max(Math.abs(left), Math.abs(right));
            float ceilingGain = peak > ceiling ? ceiling / peak : 1;
            limiter = Math.min(ceilingGain, limiter + (1 - limiter) * limiterRelease);
            long index = (long) frame * 2;
            pcm.setAtIndex(FLOAT, index, dry * pcm.getAtIndex(FLOAT, index) + left * limiter);
            pcm.setAtIndex(FLOAT, index + 1, dry * pcm.getAtIndex(FLOAT, index + 1) + right * limiter);
            offset += StemCache.FLOATS_PER_FRAME;
        }
        return mixed;
    }

    private void applyTarget(MixSettings settings, StemCache.Entry entry) {
        lastSettings = settings;
        lastEntry = entry;
        boolean dry = settings.original();
        float compensation = dry || entry.loudness() == null ? 1 : entry.loudness().gainFor(settings);
        for (int track = 0; track < DRY; track++) {
            targets[track] = dry ? 0 : settings.gain(track) * compensation;
        }
        targets[DRY] = dry ? 1 : 0;
        rampRemaining = 0;
        for (int index = 0; index <= DRY; index++) {
            increments[index] = (targets[index] - gains[index]) / rampFrames;
            if (targets[index] != gains[index]) {
                rampRemaining = rampFrames;
            }
        }
    }

    private void advanceGains() {
        if (--rampRemaining == 0) {
            System.arraycopy(targets, 0, gains, 0, gains.length);
            return;
        }
        for (int index = 0; index <= DRY; index++) {
            gains[index] += increments[index];
        }
    }

    private void reset(float dry) {
        if (lastEntry != null || gains[DRY] != dry) {
            Arrays.fill(gains, 0);
            gains[DRY] = dry;
        }
        lastEntry = null;
        lastSettings = null;
        rampRemaining = 0;
        limiter = 1;
        nextFrame = -1;
    }
}
