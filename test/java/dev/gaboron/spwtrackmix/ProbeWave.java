package dev.gaboron.spwtrackmix;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

final class ProbeWave {
    public static void main(String[] args) throws IOException {
        write(Path.of(args[0]));
    }

    static void write(Path target) throws IOException {
        int frameBytes = ProbeSignal.CHANNELS * Short.BYTES;
        var data = ByteBuffer.allocate(ProbeSignal.FRAME_COUNT * frameBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < ProbeSignal.FRAME_COUNT; frame++) {
            for (int channel = 0; channel < ProbeSignal.CHANNELS; channel++) {
                data.putShort(ProbeSignal.originalSample(frame, channel));
            }
        }

        Files.createDirectories(target.toAbsolutePath().getParent());
        var format = new AudioFormat(ProbeSignal.SAMPLE_RATE, 16, ProbeSignal.CHANNELS, true, false);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(data.array()), format,
                ProbeSignal.FRAME_COUNT)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, target.toFile());
        }
    }
}
