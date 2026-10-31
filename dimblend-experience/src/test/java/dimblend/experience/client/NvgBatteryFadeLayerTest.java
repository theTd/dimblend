package dimblend.experience.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NvgBatteryFadeLayerTest {

    /** 用户给定的锚点：电量(%) -> 遮罩不透明度(%)。2026-10-31 口径。 */
    private static final double[][] ANCHORS = {
            {60.0, 25.0},
            {50.0, 35.0},
            {40.0, 45.0},
            {30.0, 55.0},
            {20.0, 75.0},
            {10.0, 85.0},
            {0.0, 95.0},
    };

    /** 不透明度百分比 -> 0-255 alpha，四舍五入。 */
    private static int alphaOf(double opacityPercent) {
        return (int) Math.round(255.0 * opacityPercent / 100.0);
    }

    @Test
    void anchorPointsMapExactly() {
        for (double[] anchor : ANCHORS) {
            double percent = anchor[0];
            double opacity = anchor[1];
            assertEquals(alphaOf(opacity), NvgBatteryFadeLayer.dimAlpha(percent),
                    "battery " + percent + "% -> opacity " + opacity + "%");
        }
    }

    @Test
    void anchorConcreteAlphaValues() {
        // 25% -> 64, 35% -> 89, 45% -> 115, 55% -> 140, 75% -> 191, 85% -> 217, 95% -> 242
        assertEquals(0x40, NvgBatteryFadeLayer.dimAlpha(60.0));
        assertEquals(0x59, NvgBatteryFadeLayer.dimAlpha(50.0));
        assertEquals(0x73, NvgBatteryFadeLayer.dimAlpha(40.0));
        assertEquals(0x8C, NvgBatteryFadeLayer.dimAlpha(30.0));
        assertEquals(0xBF, NvgBatteryFadeLayer.dimAlpha(20.0));
        assertEquals(0xD9, NvgBatteryFadeLayer.dimAlpha(10.0));
        assertEquals(0xF2, NvgBatteryFadeLayer.dimAlpha(0.0));
    }

    @Test
    void noDimAboveSixtyPercent() {
        assertEquals(0, NvgBatteryFadeLayer.dimAlpha(60.5));
        assertEquals(0, NvgBatteryFadeLayer.dimAlpha(75.0));
        assertEquals(0, NvgBatteryFadeLayer.dimAlpha(100.0));
    }

    @Test
    void interpolatesLinearlyBetweenAnchors() {
        // 55% 在 60->50 之间 = 30% 不透明度
        assertEquals(alphaOf(30.0), NvgBatteryFadeLayer.dimAlpha(55.0));
        // 45% 在 50->40 之间 = 40%
        assertEquals(alphaOf(40.0), NvgBatteryFadeLayer.dimAlpha(45.0));
        // 35% 在 40->30 之间 = 50%
        assertEquals(alphaOf(50.0), NvgBatteryFadeLayer.dimAlpha(35.0));
        // 25% 在 30->20 之间 = 65%（斜率更陡那一段的中点）
        assertEquals(alphaOf(65.0), NvgBatteryFadeLayer.dimAlpha(25.0));
        // 15% 在 20->10 之间 = 80%
        assertEquals(alphaOf(80.0), NvgBatteryFadeLayer.dimAlpha(15.0));
        // 5% 在 10->0 之间 = 90%
        assertEquals(alphaOf(90.0), NvgBatteryFadeLayer.dimAlpha(5.0));
    }

    @Test
    void steepThirtiesToTwentiesSegmentIsNotStretchedFlat() {
        // 若把 30->20 拉成与 40->60 同样的斜率，25% 处约 50%，必须与实测 65% 区分开
        int steepMid = NvgBatteryFadeLayer.dimAlpha(25.0);
        int flatMid = NvgBatteryFadeLayer.dimAlpha(35.0);
        assertTrue(steepMid - flatMid >= 30,
                "30->20 segment must be visibly steeper: 25%=" + steepMid + ", 35%=" + flatMid);
    }

    @Test
    void alphaIsNonIncreasingAsBatteryRises() {
        // 电量升高时遮罩不增（即电量下降时遮罩不减）
        int prev = Integer.MIN_VALUE;
        for (double percent = -1.0; percent <= 70.0; percent += 0.5) {
            int alpha = NvgBatteryFadeLayer.dimAlpha(percent);
            assertTrue(prev == Integer.MIN_VALUE || alpha <= prev,
                    "alpha must not rise as percent rises: " + percent);
            prev = alpha;
        }
    }

    @Test
    void negativePercentClampsToZeroBatteryAnchor() {
        assertEquals(NvgBatteryFadeLayer.dimAlpha(0.0), NvgBatteryFadeLayer.dimAlpha(-5.0));
        assertEquals(NvgBatteryFadeLayer.dimAlpha(0.0), NvgBatteryFadeLayer.dimAlpha(-1000.0));
    }

    @Test
    void alphaStaysInByteRange() {
        for (double percent = -50.0; percent <= 150.0; percent += 0.5) {
            int alpha = NvgBatteryFadeLayer.dimAlpha(percent);
            assertTrue(alpha >= 0 && alpha <= 0xFF, "alpha out of 0-255 at " + percent + ": " + alpha);
        }
    }
}