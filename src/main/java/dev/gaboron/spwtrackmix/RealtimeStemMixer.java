package dev.gaboron.spwtrackmix;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class RealtimeStemMixer {
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final int DRY = MixSettings.STEMS;
    private final float[] gains = new float[DRY + 1];
    private final float[] targets = new float[DRY + 1];
    private final float[] increments = new float[DRY + 1];
    private final int rampFrames;
    private MixSettings lastSettings;
    private StemCache.Entry lastEntry;
    private long nextFrame = -1;
    private int rampRemaining;

    RealtimeStemMixer(int rate) {
        rampFrames = Math.max(1, rate / 100);
        reset();
    }

    void process(MemorySegment pcm, long position, int frames, StemCache.Window window, MixSettings settings) {
        if (window == null || !window.covers(position, frames)) {
            reset();
            return;
        }
        if (lastEntry != window.entry() || nextFrame != position) {
            reset();
        }
        if (settings != lastSettings || lastEntry != window.entry()) {
            applyTarget(settings, window.entry());
        }
        nextFrame = position + frames;
        if (rampRemaining == 0 && targets[DRY] == 1) {
            return;
        }

        float[] samples = window.samples();
        int offset = Math.toIntExact(position - window.start()) * StemCache.FLOATS_PER_FRAME;
        for (int frame = 0; frame < frames; frame++) {
            if (rampRemaining > 0) {
                advanceGains();
            } else if (targets[DRY] == 1) {
                return;
            }

            float dry = gains[DRY];
            long index = (long) frame * 2;
            float left = dry == 0 ? 0 : pcm.getAtIndex(FLOAT, index) * dry;
            float right = dry == 0 ? 0 : pcm.getAtIndex(FLOAT, index + 1) * dry;
            for (int track = 0; track < DRY; track++) {
                int stem = offset + track * 2;
                left += samples[stem] * gains[track];
                right += samples[stem + 1] * gains[track];
            }
            pcm.setAtIndex(FLOAT, index, left);
            pcm.setAtIndex(FLOAT, index + 1, right);
            offset += StemCache.FLOATS_PER_FRAME;
        }
    }

    private void applyTarget(MixSettings settings, StemCache.Entry entry) {
        lastSettings = settings;
        lastEntry = entry;
        boolean dry = settings.original();
        float possiblePeak = 0;
        for (int track = 0; track < DRY; track++) {
            targets[track] = dry ? 0 : settings.gain(track);
            possiblePeak += entry.peak(track) * targets[track];
        }

        // A constant shared gain preserves the relative gains and avoids pumping.
        float headroom = possiblePeak <= 0.995f ? 1f : 0.995f / possiblePeak;
        targets[DRY] = dry ? 1 : 0;
        for (int track = 0; track < DRY; track++) {
            targets[track] *= headroom;
        }
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

    private void reset() {
        if (lastEntry == null && gains[DRY] == 1) {
            return;
        }
        java.util.Arrays.fill(gains, 0);
        gains[DRY] = 1;
        lastEntry = null;
        lastSettings = null;
        rampRemaining = 0;
    }
}
