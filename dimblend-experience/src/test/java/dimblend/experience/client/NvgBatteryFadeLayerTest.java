package dimblend.experience.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 夜视低电量渐褪的 10 档阶梯口径（Audit Default #10）：
 * 50% 起每完整下降 5% 更新一档（向下取整），每档 +10% 不透明度，0% 全黑。
 */
class NvgBatteryFadeLayerTest {

    private static final float EPS = 1.0e-6f;

    @Test
    void noDimAtOrAboveFirstDrop() {
        assertAnchor(100.0, 0.0f, 0);
        assertAnchor(50.0, 0.0f, 0);
        assertAnchor(49.0, 0.0f, 0);
        assertAnchor(45.01, 0.0f, 0);
        assertAnchor(Double.NaN, 0.0f, 0);
    }

    @Test
    void exactTenStepFloorAnchors() {
        assertAnchor(45.0, 0.1f, 26);
        assertAnchor(40.0, 0.2f, 51);
        assertAnchor(35.0, 0.3f, 77);
        assertAnchor(30.0, 0.4f, 102);
        assertAnchor(25.0, 0.5f, 128);
        assertAnchor(20.0, 0.6f, 153);
        assertAnchor(15.0, 0.7f, 179);
        assertAnchor(10.0, 0.8f, 204);
        assertAnchor(5.0, 0.9f, 230);
        assertAnchor(0.01, 0.9f, 230);
        assertAnchor(0.0, 1.0f, 255);
        assertAnchor(-5.0, 1.0f, 255);
    }

    @Test
    void alphaSaturatesAtOrBelowZero() {
        assertEquals(255, NvgBatteryFadeLayer.computeAlpha(0.0));
        assertEquals(255, NvgBatteryFadeLayer.computeAlpha(-1.0));
        assertEquals(255, NvgBatteryFadeLayer.computeAlpha(-1000.0));
        assertEquals(10, NvgBatteryFadeLayer.TOTAL_STEPS);
    }

    @Test
    void alphaIsNonIncreasingAsBatteryRises() {
        int previous = 255;
        for (double percent = -1.0; percent <= 60.0; percent += 0.25) {
            int alpha = NvgBatteryFadeLayer.computeAlpha(percent);
            assertTrue(alpha <= previous,
                    "alpha must not rise as percent rises: " + percent + " -> " + alpha + " > " + previous);
            previous = alpha;
        }
    }

    @Test
    void alphaStaysInByteRange() {
        for (double percent = -50.0; percent <= 150.0; percent += 0.5) {
            int alpha = NvgBatteryFadeLayer.computeAlpha(percent);
            assertTrue(alpha >= 0 && alpha <= 0xFF, "alpha out of 0-255 at " + percent + ": " + alpha);
        }
    }

    @Test
    void opacityIsQuantizedToTenPercentSteps() {
        for (double percent = -10.0; percent <= 60.0; percent += 0.5) {
            float opacity = NvgBatteryFadeLayer.computeOpacity(percent);
            int tenths = Math.round(opacity * 10.0f);
            assertTrue(tenths >= 0 && tenths <= 10 && Math.abs(opacity - tenths / 10.0f) < EPS,
                    "opacity not on a 10% step at " + percent + ": " + opacity);
        }
    }

    private static void assertAnchor(double percent, float expectedOpacity, int expectedAlpha) {
        assertEquals(expectedOpacity, NvgBatteryFadeLayer.computeOpacity(percent), EPS,
                "opacity mismatch at " + percent);
        assertEquals(expectedAlpha, NvgBatteryFadeLayer.computeAlpha(percent),
                "alpha mismatch at " + percent);
    }
}
