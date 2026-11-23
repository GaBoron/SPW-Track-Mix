package dev.gaboron.spwtrackmix;

record AudioFormat(int rate, int frames, int frameBytes) {
    static final int FLOAT_FRAME_BYTES = 2 * Float.BYTES;

    boolean matches(StemCache.Entry entry) {
        return rate == entry.rate() && frames == entry.frames();
    }
}
