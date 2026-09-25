package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 分液池保湿/催熟数值换算（纯函数，见 {@link IrrigationMath}）。 */
class IrrigationMathTest {

    @Test
    void coverageIs2r1x2r1OnSameLayerAtRadius5() {
        assertArrayEquals(
                new double[]{95.0D, 64.0D, -45.0D, 106.0D, 65.0D, -34.0D},
                IrrigationMath.coverageBounds(100, 64, -40, 5));
    }

    @Test
    void defaultRadius3Covers7x7OnSameLayer() {
        // 默认 itemDrainCoverageRadius=3 的 7x7 口径：cells 闭区间 x-3..x+3、bounds 尺寸 7x7x1
        assertArrayEquals(
                new int[]{97, 64, -43, 103, -37},
                IrrigationMath.coverageCells(100, 64, -40, 3));
        double[] bounds = IrrigationMath.coverageBounds(100, 64, -40, 3);
        assertEquals(7.0D, bounds[3] - bounds[0]);
        assertEquals(7.0D, bounds[5] - bounds[2]);
        assertEquals(1.0D, bounds[4] - bounds[1]);
    }

    @Test
    void coverageGrowsWithRadius() {
        double[] bounds = IrrigationMath.coverageBounds(0, 64, 0, 1);
        assertEquals(3.0D, bounds[3] - bounds[0]);
        assertEquals(3.0D, bounds[5] - bounds[2]);
        assertEquals(1.0D, bounds[4] - bounds[1]);
    }

    @Test
    void coverageCellsMatchBounds() {
        assertArrayEquals(
                new int[]{95, 64, -45, 105, -35},
                IrrigationMath.coverageCells(100, 64, -40, 5));
        double[] bounds = IrrigationMath.coverageBounds(100, 64, -40, 5);
        int[] cells = IrrigationMath.coverageCells(100, 64, -40, 5);
        assertEquals(bounds[0], cells[0]);
        assertEquals(bounds[1], cells[1]);
        assertEquals(bounds[2], cells[2]);
        assertEquals(bounds[3], cells[3] + 1.0D);
        assertEquals(bounds[5], cells[4] + 1.0D);
    }

    @Test
    void coverageCellsBoundaries() {
        // 闭区间 [x-r,x+r]×[z-r,z+r]@y：半径下限/上限与负坐标
        assertArrayEquals(
                new int[]{-1, 64, -1, 1, 1},
                IrrigationMath.coverageCells(0, 64, 0, 1));
        assertArrayEquals(
                new int[]{-8, 0, -8, 8, 8},
                IrrigationMath.coverageCells(0, 0, 0, 8));
        assertArrayEquals(
                new int[]{-15, 70, -25, -5, -15},
                IrrigationMath.coverageCells(-10, 70, -20, 5));
    }

    @Test
    void intervalBeatsRoundsUpToFullInterval() {
        assertEquals(120, IrrigationMath.intervalBeats(1200));
        assertEquals(40, IrrigationMath.intervalBeats(400));
        assertEquals(3, IrrigationMath.intervalBeats(21));
        assertEquals(2, IrrigationMath.intervalBeats(20));
        assertEquals(1, IrrigationMath.intervalBeats(1));
    }

    @Test
    void thresholdStopsOnlyBelowMin() {
        assertTrue(IrrigationMath.belowThreshold(199, 200));
        assertFalse(IrrigationMath.belowThreshold(200, 200));
        assertFalse(IrrigationMath.belowThreshold(1500, 200));
    }
}
