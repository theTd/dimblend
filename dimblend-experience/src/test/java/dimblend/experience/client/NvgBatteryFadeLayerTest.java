package dimblend.experience.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NvgBatteryFadeLayerTest {

    @Test
    void noDimAtOrAboveThreshold() {
        assertEquals(0, NvgBatteryFadeLayer.dimAlpha(50.0));
        assertEquals(0, NvgBatteryFadeLayer.dimAlpha(75.0));
        assertEquals(0, NvgBatteryFadeLayer.dimAlpha(100.0));
    }

    @Test
    void tenPercentIsSevereEightyPercentOfMax() {
        // 需求增量参考：剩余 10% 时已严重干扰视线 = 上限的 80%
        int max = NvgBatteryFadeLayer.dimAlpha(0.0);
        assertEquals(0xD9, max);
        assertEquals(max * 0.8, NvgBatteryFadeLayer.dimAlpha(10.0), 1.0);
    }

    @Test
    void alphaRisesMonotonicallyAsBatteryDrains() {
        int prev = -1;
        for (double percent = 50.0; percent >= 0.0; percent -= 5.0) {
            int alpha = NvgBatteryFadeLayer.dimAlpha(percent);
            assertTrue(alpha > prev, "alpha must rise as percent drops: " + percent);
            prev = alpha;
        }
    }

    @Test
    void negativePercentClampsToMax() {
        assertEquals(NvgBatteryFadeLayer.dimAlpha(0.0), NvgBatteryFadeLayer.dimAlpha(-5.0));
    }
}
