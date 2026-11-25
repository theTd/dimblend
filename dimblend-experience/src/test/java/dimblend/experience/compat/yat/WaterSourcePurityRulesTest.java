package dimblend.experience.compat.yat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 世界水源纯度口径（Audit Default #7 / report-w3 §11.5）。 */
class WaterSourcePurityRulesTest {

    @Test
    void untaggedFluidCountsAsDirty() {
        assertEquals(0, WaterSourcePurityRules.effectivePurity(false, 3), "没有 Purity 组件一律按脏水");
        assertEquals(0, WaterSourcePurityRules.effectivePurity(false, 0));
        assertEquals(3, WaterSourcePurityRules.effectivePurity(true, 3));
        assertEquals(0, WaterSourcePurityRules.effectivePurity(true, 0));
    }

    @Test
    void constantsMatchYatRange() {
        assertEquals(0, WaterSourcePurityRules.MIN_PURITY);
        assertEquals(3, WaterSourcePurityRules.MAX_PURITY);
        assertEquals(1, WaterSourcePurityRules.RAIN_PURITY);
        assertEquals(0, WaterSourcePurityRules.INFINITE_SOURCE_PURITY);
    }

    @Test
    void blendingTakesTheDirtierSide() {
        assertEquals(1, WaterSourcePurityRules.blendMin(3, 1));
        assertEquals(0, WaterSourcePurityRules.blendMin(2, 0));
        assertEquals(2, WaterSourcePurityRules.blendMin(2, 2));
    }

    @Test
    void rainAndInfiniteSourcesOnlyLowerPurity() {
        assertEquals(1, WaterSourcePurityRules.capAt(3, WaterSourcePurityRules.RAIN_PURITY));
        assertEquals(1, WaterSourcePurityRules.capAt(1, WaterSourcePurityRules.RAIN_PURITY));
        assertEquals(0, WaterSourcePurityRules.capAt(2, WaterSourcePurityRules.INFINITE_SOURCE_PURITY));
    }

    @Test
    void blockPurityEncodingRoundTrips() {
        for (int purity = 0; purity <= 3; purity++) {
            assertEquals(purity, WaterSourcePurityRules.decodeBlockPurity(
                    WaterSourcePurityRules.encodeBlockPurity(purity)), "purity " + purity);
        }
        assertEquals(-1, WaterSourcePurityRules.decodeBlockPurity(0), "0 = 未设置");
        assertEquals(-1, WaterSourcePurityRules.decodeBlockPurity(-1));
        assertEquals(3, WaterSourcePurityRules.decodeBlockPurity(9), "越界值夹到 3");
    }

    @Test
    void mergeKeepsDirtierOfBoth() {
        assertEquals(1, WaterSourcePurityRules.mergeBlockPurity(-1, 1), "未设置时直接采用");
        assertEquals(0, WaterSourcePurityRules.mergeBlockPurity(2, 0), "已有 2 + 进来 0 = 0");
        assertEquals(2, WaterSourcePurityRules.mergeBlockPurity(2, 3), "已有 2 + 进来 3 = 2");
    }

    @Test
    void clamping() {
        assertEquals(0, WaterSourcePurityRules.clampPurity(-7));
        assertEquals(3, WaterSourcePurityRules.clampPurity(7));
        assertEquals(2, WaterSourcePurityRules.clampPurity(2));
    }

    @Test
    void onlyRealWaterIsStamped() {
        assertTrue(WaterSourcePurityRules.isWater(false, true));
        assertFalse(WaterSourcePurityRules.isWater(true, false), "空栈");
        assertFalse(WaterSourcePurityRules.isWater(false, false), "非水");
    }
}
