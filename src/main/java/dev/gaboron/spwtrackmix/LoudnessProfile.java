package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Song-wide K-weighted powers keep normalization independent of the playback position. */
final class LoudnessProfile {
    private static final int LANES = MixSettings.STEMS;
    private static final int PAIRS = LANES * (LANES + 1) / 2;
    private static final double MINIMUM_POWER = 1e-8;
    private static final double MAXIMUM_BOOST = 8;
    static final int BYTES = (PAIRS + 2) * Double.BYTES;

    private final double originalPower;
    private final double[] correlations;

    private LoudnessProfile(double originalPower, double[] correlations) {
        this.originalPower = originalPower;
        this.correlations = correlations;
    }

    float gainFor(MixSettings settings) {
        int tracks = 0;
        for (int lane = 0; lane < LANES; lane++) {
            if (settings.gain(lane) > 0) {
                tracks |= 1 << lane;
            }
        }
        if (tracks == 0 || originalPower <= MINIMUM_POWER) {
            return 1;
        }
        // Measure each active stem at unity so user faders still control the level.
        double power = 0;
        int pair = 0;
        for (int first = 0; first < LANES; first++) {
            for (int second = 0; second <= first; second++, pair++) {
                if ((tracks & (1 << first)) != 0 && (tracks & (1 << second)) != 0) {
                    power += (first == second ? 1 : 2) * correlations[pair];
                }
            }
        }
        if (power <= MINIMUM_POWER) {
            return 1;
        }
        return (float) Math.min(MAXIMUM_BOOST, Math.sqrt(originalPower / power));
    }

    void write(ByteBuffer header) {
        int start = header.position();
        header.putDouble(originalPower);
        for (double product : correlations) {
            header.putDouble(product);
        }
        header.putLong(checksum(header, start));
    }

    static LoudnessProfile read(ByteBuffer header) throws IOException {
        int start = header.position();
        double reference = header.getDouble();
        var matrix = new double[PAIRS];
        for (int pair = 0; pair < PAIRS; pair++) {
            matrix[pair] = header.getDouble();
            if (!Double.isFinite(matrix[pair])) {
                throw new IOException("音轨缓存响度数据无效");
            }
        }
        if (!Double.isFinite(reference) || reference < 0 || header.getLong() != checksum(header, start)) {
            throw new IOException("音轨缓存响度完整性检查失败");
        }
        return new LoudnessProfile(reference, matrix);
    }

    private static long checksum(ByteBuffer header, int start) {
        var crc = new CRC32C();
        crc.update(header.array(), start, BYTES - Long.BYTES);
        return crc.getValue();
    }

    static final class Analysis {
        private final Weighting[] stems = new Weighting[LANES * 2];
        private final Weighting[] original = new Weighting[2];
        private final double[] weighted = new double[LANES * 2];
        private final double[] correlated = new double[PAIRS];
        private double reference;
        private long frames;

        Analysis(int rate) {
            for (int index = 0; index < stems.length; index++) {
                stems[index] = new Weighting(rate);
            }
            for (int channel = 0; channel < 2; channel++) {
                original[channel] = new Weighting(rate);
            }
        }

        void accept(ByteBuffer data, int offset, ByteBuffer raw, int sourceOffset) throws IOException {
            double left = 0;
            double right = 0;
            for (int stem = 0; stem < LANES; stem++) {
                int sample = stem * 2;
                int bytes = offset + sample * Float.BYTES;
                weighted[sample] = stems[sample].feed(data.getFloat(bytes));
                weighted[sample + 1] = stems[sample + 1].feed(data.getFloat(bytes + Float.BYTES));
                left += weighted[sample];
                right += weighted[sample + 1];
            }
            if (raw != null) {
                float dryLeft = raw.getFloat(sourceOffset);
                float dryRight = raw.getFloat(sourceOffset + Float.BYTES);
                if (!Float.isFinite(dryLeft) || !Float.isFinite(dryRight)) {
                    throw new IOException("原曲响度分析遇到无效样本");
                }
                left = original[0].feed(dryLeft);
                right = original[1].feed(dryRight);
            }
            reference += left * left + right * right;
            int pair = 0;
            for (int first = 0; first < weighted.length; first += 2) {
                for (int second = 0; second <= first; second += 2) {
                    correlated[pair++] += weighted[first] * weighted[second]
                            + weighted[first + 1] * weighted[second + 1];
                }
            }
            frames++;
        }

        LoudnessProfile result() {
            var average = correlated.clone();
            for (int index = 0; index < average.length; index++) {
                average[index] /= frames;
            }
            return new LoudnessProfile(reference / frames, average);
        }
    }

    private static final class Weighting {
        private final Filter shelf;
        private final Filter highPass;

        Weighting(int rate) {
            // BS.1770 pre-filter and RLB stage, transformed for the source sample rate.
            double k = Math.tan(Math.PI * 1681.974450955533 / rate);
            double q = 0.7071752369554196;
            double high = Math.pow(10, 3.999843853973347 / 20);
            double middle = Math.pow(high, 0.4996667741545416);
            double denominator = 1 + k / q + k * k;
            shelf = new Filter(
                    (high + middle * k / q + k * k) / denominator,
                    2 * (k * k - high) / denominator,
                    (high - middle * k / q + k * k) / denominator,
                    2 * (k * k - 1) / denominator,
                    (1 - k / q + k * k) / denominator);

            k = Math.tan(Math.PI * 38.13547087602444 / rate);
            q = 0.5003270373238773;
            denominator = 1 + k / q + k * k;
            highPass = new Filter(1, -2, 1, 2 * (k * k - 1) / denominator,
                    (1 - k / q + k * k) / denominator);
        }

        double feed(double input) {
            return highPass.feed(shelf.feed(input));
        }
    }

    private static final class Filter {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;

        Filter(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        double feed(double value) {
            double output = b0 * value + z1;
            z1 = b1 * value - a1 * output + z2;
            z2 = b2 * value - a2 * output;
            return output;
        }
    }
}
