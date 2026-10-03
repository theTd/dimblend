package dimblend.radio.acoustics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EchoDecorrelatorTest {
    private static final int RATE = 44100, FRAME = 512, BLOCKS = 400;

    @Test void bothEarsKeepTheirLevelButNoLongerHearTheSameSignal() {
        var random = new Random(7);
        float[] source = new float[BLOCKS * FRAME];
        for (int i = 0; i < source.length; i++) source[i] = (float) random.nextGaussian() * 0.1f;
        float[][] out = decorrelate(source, source);
        // After the filters filled up.
        double level = energy(source, RATE / 10);
        assertEquals(level, energy(out[0], RATE / 10), level * 0.02, "each ear keeps its level");
        assertEquals(level, energy(out[1], RATE / 10), level * 0.02);
        assertEquals(1, interauralCorrelation(source, source), 1e-6);
        double correlation = interauralCorrelation(out[0], out[1]);
        assertTrue(correlation < 0.4, "a diffuse field's ears differ (cross-correlation within 1 ms): " + correlation);
    }

    @Test void bassStaysCoherentBetweenTheEars() {
        float[] tone = new float[BLOCKS * FRAME];
        for (int i = 0; i < tone.length; i++) tone[i] = (float) Math.sin(2 * Math.PI * 60 * i / RATE) * 0.5f;
        float[][] out = decorrelate(tone, tone);
        double correlation = interauralCorrelation(out[0], out[1]);
        assertTrue(correlation > 0.9, "60 Hz reaches both ears alike in a room too: " + correlation);
    }

    @Test void resetLeavesNoRinging() {
        var decorrelator = new EchoDecorrelator(RATE);
        float[] left = new float[FRAME], right = new float[FRAME];
        left[0] = right[0] = 1;
        decorrelator.process(left, right);
        decorrelator.reset();
        float[] quietLeft = new float[FRAME], quietRight = new float[FRAME];
        decorrelator.process(quietLeft, quietRight);
        for (int i = 0; i < FRAME; i++) {
            assertEquals(0, quietLeft[i]);
            assertEquals(0, quietRight[i]);
        }
    }

    private static float[][] decorrelate(float[] leftIn, float[] rightIn) {
        var decorrelator = new EchoDecorrelator(RATE);
        float[][] out = new float[2][leftIn.length];
        for (int block = 0; block < BLOCKS; block++) {
            float[] left = new float[FRAME], right = new float[FRAME];
            System.arraycopy(leftIn, block * FRAME, left, 0, FRAME);
            System.arraycopy(rightIn, block * FRAME, right, 0, FRAME);
            decorrelator.process(left, right);
            System.arraycopy(left, 0, out[0], block * FRAME, FRAME);
            System.arraycopy(right, 0, out[1], block * FRAME, FRAME);
        }
        return out;
    }

    private static double energy(float[] signal, int from) {
        double sum = 0;
        for (int i = from; i < signal.length; i++) sum += signal[i] * (double) signal[i];
        return sum;
    }

    /** Median over 50 ms windows of the largest normalized cross-correlation within 1 ms, after a settling second. */
    private static double interauralCorrelation(float[] left, float[] right) {
        int window = RATE / 20, lag = RATE / 1000;
        List<Double> values = new ArrayList<>();
        for (int start = RATE + lag; start + window + lag < left.length; start += window) {
            double best = 0, leftEnergy = 0;
            for (int i = 0; i < window; i++) leftEnergy += left[start + i] * (double) left[start + i];
            for (int shift = -lag; shift <= lag; shift++) {
                double product = 0, rightEnergy = 0;
                for (int i = 0; i < window; i++) {
                    product += left[start + i] * (double) right[start + i + shift];
                    rightEnergy += right[start + i + shift] * (double) right[start + i + shift];
                }
                best = Math.max(best, Math.abs(product) / Math.sqrt(leftEnergy * rightEnergy + 1e-30));
            }
            values.add(best);
        }
        values.sort(null);
        return values.get(values.size() / 2);
    }
}
