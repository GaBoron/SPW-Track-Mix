package dev.gaboron.spwtrackmix;

final class ProbeSignal {
    static final int SAMPLE_RATE = 44_100;
    static final int CHANNELS = 2;
    static final int FRAME_COUNT = SAMPLE_RATE * 8;
    private static final int[] TONES = {180, 250, 350, 490, 690, 970};
    private static final int PERIOD = SAMPLE_RATE / 10;

    private ProbeSignal() {}

    static short originalSample(int frame, int channel) {
        int sample = 0;
        for (int lane = 0; lane < TONES.length; lane++) {
            sample += stemSample(lane, frame, channel);
        }
        return (short) sample;
    }

    static short stemSample(int lane, int frame, int channel) {
        double time = (frame % PERIOD) / (double) SAMPLE_RATE;
        double phase = 2.0 * Math.PI * TONES[lane] * time + Math.PI / 4 * channel;
        return (short) Math.round(1024 * Math.sin(phase));
    }
}
