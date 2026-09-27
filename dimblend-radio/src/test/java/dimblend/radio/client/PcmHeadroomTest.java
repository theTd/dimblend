package dimblend.radio.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按曲余量线性预放大：有余量放足、满幅不动、不越顶；side 10 永远还原原曲，音量随 side 单调、按余量封顶。
 */
class PcmHeadroomTest {
    private static final float MAX = 1.5f;
    private static final float RATE = 44100f;

    @Test
    void quietTrackGetsFullBoostLinearly() {
        short[] x = sine(12000, 0.5);
        byte[] data = toBytes(x);
        assertEquals(1.5f, PcmHeadroom.applyMaxBoost(data, MAX), 1e-6);
        short[] y = toShorts(data);
        for (int i = 0; i < x.length; i++) {
            assertEquals(Math.round(x[i] * 1.5f), y[i], "sample " + i);
        }
    }

    @Test
    void fullScaleTrackIsUntouched() {
        byte[] before = toBytes(sine(32767, 0.5));
        byte[] data = before.clone();
        assertEquals(1.0f, PcmHeadroom.applyMaxBoost(data, MAX), 1e-6);
        assertArrayEquals(before, data);
    }

    @Test
    void partialHeadroomStopsAtCeiling() {
        byte[] data = toBytes(sine(25000, 0.5));
        float boost = PcmHeadroom.applyMaxBoost(data, MAX);
        assertEquals((float) PcmHeadroom.CEILING / 25000, boost, 1e-6);
        assertTrue(PcmHeadroom.peak(data) <= PcmHeadroom.CEILING, "peak " + PcmHeadroom.peak(data));
    }

    @Test
    void side10AlwaysRestoresOriginalLevel() {
        for (float boost : new float[] {1.0f, 1.266f, 1.5f}) {
            assertEquals(1.0f, PcmHeadroom.channelGain(100, boost) * boost, 1e-6, "boost " + boost);
        }
    }

    @Test
    void levelRisesWithSideAndCapsAtHeadroom() {
        for (float boost : new float[] {1.0f, 1.266f, 1.5f}) {
            float prev = 0;
            for (int side = 1; side <= 15; side++) {
                float gain = PcmHeadroom.channelGain(side * 10, boost);
                assertTrue(gain <= 1.0f, "gain " + gain);
                float level = gain * boost; // 相对原曲的实际响度
                assertTrue(level >= prev, "side " + side + " quieter than side " + (side - 1));
                assertEquals(Math.min(side / 10.0f, boost), level, 1e-6, "side " + side + " boost " + boost);
                prev = level;
            }
        }
    }

    @Test
    void silenceIsUnchanged() {
        byte[] data = new byte[2000];
        assertEquals(MAX, PcmHeadroom.applyMaxBoost(data, MAX), 1e-6);
        assertArrayEquals(new byte[2000], data);
    }

    private static short[] sine(int amplitude, double seconds) {
        short[] out = new short[(int) (seconds * RATE)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * 440 * i / RATE));
        }
        return out;
    }

    private static byte[] toBytes(short[] x) {
        byte[] data = new byte[x.length * 2];
        for (int i = 0; i < x.length; i++) {
            data[i * 2] = (byte) x[i];
            data[i * 2 + 1] = (byte) (x[i] >> 8);
        }
        return data;
    }

    private static short[] toShorts(byte[] data) {
        short[] y = new short[data.length / 2];
        for (int i = 0; i < y.length; i++) {
            y[i] = (short) ((data[i * 2] & 0xFF) | (data[i * 2 + 1] << 8));
        }
        return y;
    }
}
