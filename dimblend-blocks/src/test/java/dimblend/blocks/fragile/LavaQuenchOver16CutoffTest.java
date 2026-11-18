package dimblend.blocks.fragile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 水桶回溯超过 16 格时的紧急截断自检：从右键格（步 0）起前 8 格纯岩浆流被删除，
 * 第 8 格及更远不动，源头永远不删。
 */
class LavaQuenchOver16CutoffTest {

    @Test
    void productionLimitIsEightBlocks() {
        assertEquals(8, WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT);
    }

    @Test
    void firstEightFlowingCellsAreDeleted() {
        for (int step = 0; step < WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT; step++) {
            assertTrue(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(
                            step, WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT, true, false),
                    "step " + step + " 必须是截断段");
        }
    }

    @Test
    void eighthStepAndBeyondAreKept() {
        for (int step : new int[]{8, 9, 16, 127}) {
            assertFalse(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(
                            step, WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT, true, false),
                    "step " + step + " 不在截断段");
        }
    }

    @Test
    void sourceCellsAreNeverDeleted() {
        for (int step = 0; step < WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT; step++) {
            assertFalse(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(
                            step, WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT, true, true),
                    "源头不得被截断删除，step " + step);
        }
    }

    @Test
    void nonLavaCellsAreNeverDeleted() {
        for (boolean source : new boolean[]{true, false}) {
            assertFalse(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(
                    0, WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT, false, source));
        }
    }

    @Test
    void negativeStepIsNotDeletable() {
        assertFalse(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(
                -1, WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT, true, false));
    }

    @Test
    void shorterPrefixIsHonoured() {
        assertTrue(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(2, 3, true, false));
        assertFalse(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(3, 3, true, false));
        assertFalse(WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(0, 0, true, false));
    }
}
