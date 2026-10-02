package dimblend.radio.acoustics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PropagationDelayLineTest {
    private static final int RATE = 48000;
    private static final int FRAME = SteamRenderer.FRAME;

    @Test void aStillSourceIsDelayedByTheScaledPropagationTime() {
        for (double scale : new double[] {1, 0.25}) {
            var line = new PropagationDelayLine(RATE, () -> scale);
            double distance = PropagationDelayLine.SPEED_OF_SOUND / 10; // 100 ms
            line.process(new float[FRAME], distance);
            int expected = (int) Math.round(RATE * 0.1 * scale);
            int arrival = -1;
            for (int block = 0; block < 20 && arrival < 0; block++) {
                float[] samples = new float[FRAME];
                if (block == 0) samples[0] = 1;
                line.process(samples, distance);
                for (int i = 0; i < FRAME && arrival < 0; i++) if (Math.abs(samples[i]) > 0.5f) arrival = block * FRAME + i;
            }
            assertEquals(expected, arrival, "scale " + scale);
        }
    }

    @Test void zeroDopplerPassesAudioThroughUntouched() {
        var line = new PropagationDelayLine(RATE, () -> 0);
        for (int block = 0; block < 10; block++) {
            float[] samples = new float[FRAME];
            for (int i = 0; i < FRAME; i++) samples[i] = (float) Math.sin((block * FRAME + i) * 0.01);
            float[] original = samples.clone();
            line.process(samples, 10 + block * 3);
            assertArrayEquals(original, samples, 1e-6f);
        }
    }

    @Test void walkingGlidesToAReducedSteadyDopplerShiftDespiteJitteryPoses() {
        double scale = 0.25, speed = 4.3;
        var line = new PropagationDelayLine(RATE, () -> scale);
        double blockSeconds = (double) FRAME / RATE, distance = 10, posed = distance;
        double previous = Double.NaN, previousRate = 0, largestStep = 0, firstRate = Double.NaN, settled = 0;
        int settledBlocks = 0;
        for (int block = 0; block < 300; block++) {
            if (block >= 20) distance += speed * blockSeconds;
            // Poses arrive with render frames, not audio blocks: two of every three blocks see one.
            if (block % 3 != 2) posed = distance;
            line.process(new float[FRAME], posed);
            double delay = line.delaySamples();
            if (!Double.isNaN(previous)) {
                double rate = (delay - previous) / FRAME;
                if (block == 21) firstRate = rate;
                if (block > 20) largestStep = Math.max(largestStep, Math.abs(rate - previousRate));
                if (block >= 150) { settled += rate; settledBlocks++; }
                previousRate = rate;
            }
            previous = delay;
        }
        double physical = speed / PropagationDelayLine.SPEED_OF_SOUND;
        assertEquals(physical * scale, settled / settledBlocks, physical * scale * 0.1, "steady pitch shift is the scaled Doppler shift");
        assertTrue(firstRate < physical * scale * 0.3, "starting to walk glides instead of stepping: " + firstRate);
        assertTrue(largestStep < physical * scale * 0.2, "frame-rate pose jitter must not modulate pitch: " + largestStep);
    }

    @Test void teleportsCrossfadeToTheNewDelayAtOnce() {
        var line = new PropagationDelayLine(RATE, () -> 1);
        float[] samples = null;
        for (int block = 0; block < 26; block++) {
            samples = new float[FRAME];
            for (int i = 0; i < FRAME; i++) samples[i] = (float) Math.sin((block * FRAME + i) * 0.06) * 0.2f;
            line.process(samples, block < 25 ? 4 : 68.6);
        }
        assertEquals(68.6 / PropagationDelayLine.SPEED_OF_SOUND * RATE, line.delaySamples(), 1e-6);
        double signal = 0, roughness = 0;
        for (int i = 2; i < FRAME; i++) {
            double curvature = samples[i] - 2 * samples[i - 1] + samples[i - 2];
            signal += samples[i] * (double) samples[i];
            roughness += curvature * curvature;
        }
        assertTrue(signal > 1e-4);
        assertTrue(roughness < signal * 0.01, "no pitch sweep across the jump, ratio=" + roughness / signal);
    }

    @Test void clearingNeverReplaysOldAudioAndRestartsAtTheTarget() {
        var line = new PropagationDelayLine(RATE, () -> 1);
        for (int block = 0; block < 20; block++) {
            float[] loud = new float[FRAME];
            java.util.Arrays.fill(loud, 0.5f);
            line.process(loud, 20);
        }
        assertTrue(line.pendingSamples() > 0);
        line.clear();
        assertEquals(0, line.pendingSamples());
        for (int block = 0; block < 10; block++) {
            float[] silent = new float[FRAME];
            line.process(silent, 40);
            for (float sample : silent) assertEquals(0, sample);
        }
        assertEquals(40 / PropagationDelayLine.SPEED_OF_SOUND * RATE, line.delaySamples(), 1e-6, "no glide from the old delay");
    }

    @Test void invalidDopplerSettingsFallBackToTheDefault() {
        String property = "dimblend.radio.acoustic.doppler";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "0");
            assertEquals(0, PropagationDelayLine.configuredScale());
            System.setProperty(property, "1");
            assertEquals(1, PropagationDelayLine.configuredScale());
            for (String invalid : new String[] {"2", "-0.5", "NaN", "fast", ""}) {
                System.setProperty(property, invalid);
                assertEquals(0.25, PropagationDelayLine.configuredScale(), 1e-6, invalid);
            }
            System.clearProperty(property);
            assertEquals(0.25, PropagationDelayLine.configuredScale(), 1e-6);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
