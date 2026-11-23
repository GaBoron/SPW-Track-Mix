package dev.gaboron.spwtrackmix;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class PcmChecks {
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final int[] TONES = {180, 250, 350, 490, 690, 970};

    public static void main(String[] args) {
        try (var arena = Arena.ofConfined()) {
            int count = 1024;
            var buffer = arena.allocate(count * ProbeSignal.FLOAT_FRAME_BYTES, Float.BYTES);
            for (int section = 0; section < 8; section++) {
                int start = section * ProbeSignal.SAMPLE_RATE + 907;
                fill(buffer, start, count);
                require(ProbeSignal.process(buffer, start, count), "valid block rejected");
                checkOutput(buffer, start, count);
            }

            int start = ProbeSignal.SAMPLE_RATE - 196;
            fill(buffer, start, count);
            require(ProbeSignal.process(buffer, start, count), "transition block rejected");
            checkOutput(buffer, start, count);

            fill(buffer, start, count);
            buffer.setAtIndex(FLOAT, count * 2 - 1, Float.NaN);
            var original = buffer.toArray(ValueLayout.JAVA_BYTE);
            require(!ProbeSignal.process(buffer, start, count), "unrelated block accepted");
            require(java.util.Arrays.equals(original, buffer.toArray(ValueLayout.JAVA_BYTE)),
                    "failed identity check touched part of the block");
            require(!ProbeSignal.process(buffer, -1, count), "negative position accepted");
            require(!ProbeSignal.process(buffer, ProbeSignal.FRAME_COUNT - 1, count), "end overrun accepted");
        }
        System.out.println("PCM probe verified: six solos, fades, exact bypass, wrong PCM and invalid positions.");
    }

    private static void fill(MemorySegment buffer, int startFrame, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < 2; channel++) {
                buffer.setAtIndex(FLOAT, frame * 2 + channel, original(startFrame + frame, channel));
            }
        }
    }

    static void checkOutput(MemorySegment buffer, int startFrame, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            int position = startFrame + frame;
            int section = position / 44100;
            int offset = position % 44100;
            for (int channel = 0; channel < 2; channel++) {
                float expected = sampleForSection(section, position, channel);
                if (section != 0 && offset < 441) {
                    float previous = sampleForSection(section - 1, position, channel);
                    expected = (expected * offset + previous * (441 - offset)) / 441;
                }
                float actual = buffer.getAtIndex(FLOAT, frame * 2 + channel);
                if (!(Math.abs(actual - expected) <= 1e-7f)) {
                    throw new AssertionError("Frame " + position + " channel " + channel
                            + " expected " + expected + " but got " + actual);
                }
            }
        }
    }

    static void checkOriginal(MemorySegment buffer, int startFrame, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < 2; channel++) {
                require(buffer.getAtIndex(FLOAT, frame * 2 + channel) == original(startFrame + frame, channel),
                        "original block changed");
            }
        }
    }

    private static float original(int frame, int channel) {
        int sample = 0;
        for (int frequency : TONES) {
            sample += tone(frequency, frame, channel);
        }
        return sample / 32768f;
    }

    private static float sampleForSection(int section, int frame, int channel) {
        return section == 0 || section == 7
                ? original(frame, channel)
                : tone(TONES[section - 1], frame, channel) / 32768f;
    }

    private static long tone(int frequency, int frame, int channel) {
        double phase = (frame % 4410) / 44100.0 * (2 * Math.PI * frequency) + channel * Math.PI / 4;
        return Math.round(Math.sin(phase) * 1024);
    }

    static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
