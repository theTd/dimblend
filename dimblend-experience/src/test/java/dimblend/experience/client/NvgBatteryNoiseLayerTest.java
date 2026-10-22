package dimblend.experience.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NvgBatteryNoiseLayerTest {

    private static final int W = 1000;
    private static final int H = 1000;

    @Test
    void noNoiseAtOrAboveThreshold() {
        assertEquals(0, NvgBatteryNoiseLayer.dotCount(50.0, W, H));
        assertEquals(0, NvgBatteryNoiseLayer.dotCount(75.0, W, H));
        assertEquals(0, NvgBatteryNoiseLayer.dotCount(100.0, W, H));
    }

    @Test
    void tenPercentIsSevereEightyPercentOfMax() {
        // 需求增量参考：剩余 10% 时已严重干扰视线 = 满密度的 80%
        int max = NvgBatteryNoiseLayer.dotCount(0.0, W, H);
        assertEquals(W * H * 0.02, max, 1.0);
        assertEquals(max * 0.8, NvgBatteryNoiseLayer.dotCount(10.0, W, H), 2.0);
    }

    @Test
    void countRisesMonotonicallyAsBatteryDrains() {
        int prev = -1;
        for (double percent = 50.0; percent >= 0.0; percent -= 5.0) {
            int dots = NvgBatteryNoiseLayer.dotCount(percent, W, H);
            assertTrue(dots > prev, "dots must rise as percent drops: " + percent);
            prev = dots;
        }
    }

    @Test
    void negativePercentClampsToMaxAndAreaScales() {
        assertEquals(NvgBatteryNoiseLayer.dotCount(0.0, W, H), NvgBatteryNoiseLayer.dotCount(-5.0, W, H));
        assertEquals(2.0 * NvgBatteryNoiseLayer.dotCount(10.0, W, H),
                NvgBatteryNoiseLayer.dotCount(10.0, 2 * W, H), 3.0);
    }
}
