package dimblend.experience.compat.cdg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CdgOilBalanceTest {

    @Test
    void zeroOrNegativeRemainsDry() {
        assertEquals(0, CdgOilBalance.scaleNewChunkBaseAmount(0, 10_000_000));
        assertEquals(0, CdgOilBalance.scaleNewChunkBaseAmount(-5, 10_000_000));
    }

    @Test
    void finiteAndFormerInfinitePeaksScaleToTenPercentWithoutBottomlessPromotion() {
        // 默认最低出油线 4,000,000 mB -> 400,000 mB
        assertEquals(400_000, CdgOilBalance.scaleNewChunkBaseAmount(4_000_000, 10_000_000));
        // 普通有限油田 7,500,000 mB -> 750,000 mB
        assertEquals(750_000, CdgOilBalance.scaleNewChunkBaseAmount(7_500_000, 10_000_000));
        // 原无限油田噪声峰值 14,000,000 mB -> 1,400,000 mB（有限）
        assertEquals(1_400_000, CdgOilBalance.scaleNewChunkBaseAmount(14_000_000, 10_000_000));
        // 外部插件直接返回 Integer.MAX_VALUE 时退化为阈值 × 10%
        assertEquals(1_000_000, CdgOilBalance.scaleNewChunkBaseAmount(Integer.MAX_VALUE, 10_000_000));
        // 极小正值保底 1 mB
        assertEquals(1, CdgOilBalance.scaleNewChunkBaseAmount(3, 10_000_000));
    }

    @Test
    void scannerThresholdsScaleProportionally() {
        int scaledMin = CdgOilBalance.scaleNewChunkThreshold(4_000_000);
        int scaledMax = CdgOilBalance.scaleNewChunkThreshold(10_000_000);
        assertEquals(400_000, scaledMin);
        assertEquals(1_000_000, scaledMax);
        assertEquals(700_000, (scaledMin + scaledMax) / 2);
    }
}
