package dev.gaboron.spwtrackmix;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/** Holds source-positioned PCM after the track mix and before SPW's master volume. */
final class MixedSpectrum {
    private static final int CAPACITY = 1 << 18;
    private static final int MASK = CAPACITY - 1;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private final float[] audio = new float[CAPACITY];
    private final int rate;
    private volatile long sequence;
    private int start, end;
    private Transform longWindow, shortWindow;

    MixedSpectrum(int rate) {
        this.rate = rate;
    }

    void write(MemorySegment pcm, int position, int frames) {
        sequence++;
        if (position != end) {
            start = position;
        }
        for (int frame = 0; frame < frames; frame++) {
            long index = 2L * frame;
            audio[(position + frame) & MASK] = (pcm.getAtIndex(FLOAT, index) + pcm.getAtIndex(FLOAT, index + 1)) * .5f;
        }
        end = position + frames;
        start = Math.max(start, end - CAPACITY);
        sequence++;
    }

    synchronized Object[] at(long positionMs) {
        int shift = rate > 96000 ? 2 : rate > 48000 ? 1 : 0;
        if (longWindow == null) {
            longWindow = new Transform(8192 << shift);
            shortWindow = new Transform(2048 << shift);
        }
        int center = (int) Math.min(Integer.MAX_VALUE, Math.max(0, positionMs) * (rate / 1000.0));
        long before = sequence;
        int from = start;
        int until = end;
        if ((before & 1) != 0 || center < from || center >= until) {
            return empty();
        }
        longWindow.capture(audio, center, from, until);
        shortWindow.capture(audio, center, from, until);
        if (sequence != before) {
            return empty();
        }
        return new Object[]{rate, longWindow.magnitudes(), shortWindow.magnitudes()};
    }

    Object[] empty() {
        return new Object[]{rate, new float[0], new float[0]};
    }

    private static final class Transform {
        private final int length;
        private final float[] real, imaginary, window, cosine, sine;
        private final int[] reversed;

        Transform(int length) {
            this.length = length;
            real = new float[length];
            imaginary = new float[length];
            window = new float[length];
            cosine = new float[length / 2];
            sine = new float[length / 2];
            reversed = new int[length];
            int shift = Integer.numberOfLeadingZeros(length) + 1;
            for (int index = 0; index < length; index++) {
                reversed[index] = Integer.reverse(index) >>> shift;
                window[index] = (float) (.5 - .5 * Math.cos(2 * Math.PI * index / length));
            }
            for (int index = 0; index < cosine.length; index++) {
                double phase = -2 * Math.PI * index / length;
                cosine[index] = (float) Math.cos(phase);
                sine[index] = (float) Math.sin(phase);
            }
        }

        void capture(float[] audio, int center, int start, int end) {
            int origin = center - length / 2;
            Arrays.fill(real, 0);
            Arrays.fill(imaginary, 0);
            for (int index = 0; index < length; index++) {
                int position = origin + index;
                if (position >= start && position < end) {
                    real[reversed[index]] = audio[position & MASK] * window[index];
                }
            }
        }

        float[] magnitudes() {
            for (int width = 2; width <= length; width *= 2) {
                int half = width / 2;
                int step = length / width;
                for (int start = 0; start < length; start += width) {
                    for (int offset = 0; offset < half; offset++) {
                        int twiddle = offset * step;
                        int left = start + offset;
                        int right = left + half;
                        float re = real[right] * cosine[twiddle] - imaginary[right] * sine[twiddle];
                        float im = real[right] * sine[twiddle] + imaginary[right] * cosine[twiddle];
                        real[right] = real[left] - re;
                        imaginary[right] = imaginary[left] - im;
                        real[left] += re;
                        imaginary[left] += im;
                    }
                }
            }
            float[] values = new float[length / 2];
            float scale = 4f / length;
            for (int index = 1; index < values.length; index++) {
                values[index] = (float) Math.sqrt(real[index] * real[index] + imaginary[index] * imaginary[index]) * scale;
            }
            return values;
        }
    }
}
