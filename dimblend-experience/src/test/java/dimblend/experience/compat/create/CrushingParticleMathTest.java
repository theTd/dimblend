package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 钉死「Create 粉碎轮粒子在 Sable plot 坐标下被 {@code float} 吞掉子格抖动」这条缺陷与修法。
 *
 * <p>红测口径：把 {@link CrushingParticleMath#exactJitteredCoordinate} 换回 Create 的
 * {@code (float) coordinate + jitter}（即改用 {@link CrushingParticleMath#quantizedJitteredCoordinate}），
 * 本类的 {@link #exactCoordinateKeepsSubBlockJitter()} 会立刻失败；把
 * {@link CrushingParticleMath#losesSubBlockJitter} 的阈值改错，
 * {@link #thresholdMatchesFloatUlp()} 会失败。</p>
 */
class CrushingParticleMathTest {

    /** Sable plot 方块坐标起点：{@code DEFAULT_ORIGIN(10000) * (1 << 7) chunks * 16}，见 {@code SubLevelContainer}。 */
    private static final int PLOT_X = 20_480_000;

    private static final float[] JITTERS = {0.0f, 0.125f, 0.25f, 0.5f, 0.75f, 0.999f};

    @Test
    void thresholdMatchesFloatUlp() {
        assertFalse(CrushingParticleMath.losesSubBlockJitter(0));
        assertFalse(CrushingParticleMath.losesSubBlockJitter(1_000));
        assertFalse(CrushingParticleMath.losesSubBlockJitter(1 << 22));
        assertFalse(CrushingParticleMath.losesSubBlockJitter((1 << 23) - 1));
        assertTrue(CrushingParticleMath.losesSubBlockJitter(1 << 23));
        assertTrue(CrushingParticleMath.losesSubBlockJitter(-(1 << 23)));
        assertTrue(CrushingParticleMath.losesSubBlockJitter(PLOT_X));

        // 阈值的物理含义：从 2^23 起 float 的 ULP 已经不小于 1，[0,1) 抖动无法表出；
        // 阈值下一格 ULP 仍是 0.5，抖动完好，所以那里必须保持 Create 原样。
        assertEquals(0.5f, Math.ulp((float) ((1 << 23) - 1)), 0.0f);
        assertEquals(1.0f, Math.ulp((float) (1 << 23)), 0.0f);
        assertEquals(2.0f, Math.ulp((float) PLOT_X), 0.0f);
        assertFalse(CrushingParticleMath.needsExactDouble(PLOT_X >> 4, 66, -(PLOT_X >> 4)));
        assertTrue(CrushingParticleMath.needsExactDouble(PLOT_X, 66, 0));
        assertTrue(CrushingParticleMath.needsExactDouble(0, 66, PLOT_X));
    }

    @Test
    void exactCoordinateKeepsSubBlockJitter() {
        for (int offset = 0; offset < 16; offset++) {
            final int coordinate = PLOT_X + offset;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (float jitter : JITTERS) {
                final double exact = CrushingParticleMath.exactJitteredCoordinate(coordinate, jitter);
                // double 口径：整数与 float 抖动都在 double 里精确，结果逐位等于算术和。
                assertEquals(coordinate + (double) jitter, exact, 0.0);
                min = Math.min(min, exact);
                max = Math.max(max, exact);
            }
            // 抖动仍然铺满整个子格区间（Create 口径下这里是 0）。
            assertTrue(max - min > 0.9, "sub-block jitter lost at " + coordinate);
        }
    }

    @Test
    void createFloatPathSnapsEveryCoordinateOntoTheEvenLattice() {
        for (int offset = 0; offset < 16; offset++) {
            final int coordinate = PLOT_X + offset;
            for (float jitter : JITTERS) {
                final double quantized = CrushingParticleMath.quantizedJitteredCoordinate(coordinate, jitter);
                // 任何抖动都被压回偶数格：整格偏移最多 1 格，且与抖动无关（→ 固定错位）。
                assertEquals(0.0, quantized % 2.0, 0.0);
                assertTrue(Math.abs(quantized - coordinate) <= 1.0);
                if ((coordinate & 1) != 0) {
                    // 奇数坐标一律被整体挪满一格：玩家看到的「从来不在正确位置」。
                    assertEquals(1.0, Math.abs(quantized - coordinate), 0.0);
                } else {
                    assertEquals(0.0, Math.abs(quantized - coordinate), 0.0);
                }
            }
        }
    }

    @Test
    void createFloatPathCollapsesAllFourDebrisParticlesOntoOnePoint() {
        final int coordinate = PLOT_X;
        for (float jitter : JITTERS) {
            assertEquals(
                    (double) coordinate,
                    CrushingParticleMath.quantizedJitteredCoordinate(coordinate, jitter),
                    0.0);
            final double exact = CrushingParticleMath.exactJitteredCoordinate(coordinate, jitter);
            assertTrue(exact >= coordinate && exact < coordinate + 1.0);
            if (jitter > 0.0f) {
                assertNotEquals((double) coordinate, exact, 0.0);
            }
        }
        // 修好后同一格里四颗碎屑坐标互不相同；Create 口径下它们完全相同（四颗叠在一个格角）。
        assertNotEquals(
                CrushingParticleMath.exactJitteredCoordinate(coordinate, JITTERS[1]),
                CrushingParticleMath.exactJitteredCoordinate(coordinate, JITTERS[4]),
                0.0);
        assertEquals(
                CrushingParticleMath.quantizedJitteredCoordinate(coordinate, JITTERS[1]),
                CrushingParticleMath.quantizedJitteredCoordinate(coordinate, JITTERS[4]),
                0.0);
    }
}
