package dev.gaboron.spwtrackmix;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** The companion test WAV starts and ends with the sum of six distinct stereo tones. */
final class ProbeSignal {
    static final String FILE_NAME = "spw-track-mix-test.wav";
    static final int SAMPLE_RATE = 44_100;
    static final int CHANNELS = 2;
    static final int FLOAT_FRAME_BYTES = CHANNELS * Float.BYTES;
    static final int FRAME_COUNT = SAMPLE_RATE * 8;

    private static final int PERIOD = SAMPLE_RATE / 10;
    private static final int RAMP_FRAMES = SAMPLE_RATE / 100;
    private static final int[] TONES = {180, 250, 350, 490, 690, 970};
    private static final short[][] STEMS = new short[TONES.length][PERIOD * CHANNELS];
    private static final short[] ORIGINAL = new short[PERIOD * CHANNELS];
    private static final float PCM16_SCALE = 1f / 32768f;

    static {
        for (int lane = 0; lane < TONES.length; lane++) {
            for (int frame = 0; frame < PERIOD; frame++) {
                double phase = 2.0 * Math.PI * TONES[lane] * frame / SAMPLE_RATE;
                int left = frame * CHANNELS;
                STEMS[lane][left] = (short) Math.round(1024 * Math.sin(phase));
                STEMS[lane][left + 1] = (short) Math.round(1024 * Math.sin(phase + Math.PI / 4));
                ORIGINAL[left] += STEMS[lane][left];
                ORIGINAL[left + 1] += STEMS[lane][left + 1];
            }
        }
    }

    private ProbeSignal() {}

    static void prepare() {
        // Initialize the fixture tables before BASS can enter the callback.
    }

    static short originalSample(int frame, int channel) {
        return ORIGINAL[frame % PERIOD * CHANNELS + channel];
    }

    static boolean process(MemorySegment buffer, int firstFrame, int frameCount) {
        if (firstFrame < 0 || frameCount <= 0 || frameCount > FRAME_COUNT - firstFrame
                || buffer.byteSize() != (long) frameCount * FLOAT_FRAME_BYTES) {
            return false;
        }

        // Check the entire block before writing so mismatched data passes through unchanged.
        for (int frame = 0; frame < frameCount; frame++) {
            int sampleIndex = frame * CHANNELS;
            int periodIndex = (firstFrame + frame) % PERIOD * CHANNELS;
            for (int channel = 0; channel < CHANNELS; channel++) {
                float value = buffer.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, sampleIndex + channel);
                if (value != ORIGINAL[periodIndex + channel] * PCM16_SCALE) {
                    return false;
                }
            }
        }

        for (int frame = 0; frame < frameCount; frame++) {
            int position = firstFrame + frame;
            int section = position / SAMPLE_RATE;
            if (section == 0) {
                continue;
            }

            int offset = position % SAMPLE_RATE;
            int sampleIndex = frame * CHANNELS;
            int periodIndex = position % PERIOD * CHANNELS;
            for (int channel = 0; channel < CHANNELS; channel++) {
                int index = periodIndex + channel;
                short current = section <= TONES.length ? STEMS[section - 1][index] : ORIGINAL[index];
                float sample = current;
                if (offset < RAMP_FRAMES) {
                    short previous = section == 1 ? ORIGINAL[index] : STEMS[section - 2][index];
                    sample = previous + (current - previous) * (offset / (float) RAMP_FRAMES);
                } else if (section > TONES.length) {
                    continue;
                }
                buffer.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, sampleIndex + channel, sample * PCM16_SCALE);
            }
        }
        return true;
    }
}
