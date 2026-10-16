package dimblend.experience.compat.cdg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** B3 波动输出取整（见 {@link CdgFluctuationMath}）：整数 rpm、不超额定（纯函数，零依赖）。 */
class CdgFluctuationMathTest {

    @Test
    void fluctuationOutputIsRoundedToWholeRpm() {
        // 96 额定：0.8×96=76.8→77、0.99×96=95.04→95
        assertEquals(77.0F, CdgFluctuationMath.fluctuatedSpeed(96.0F, 0.8F));
        assertEquals(95.0F, CdgFluctuationMath.fluctuatedSpeed(96.0F, 0.99F));
        // 256 额定：204.8→205（窗子实测小数例）
        assertEquals(205.0F, CdgFluctuationMath.fluctuatedSpeed(256.0F, 0.8F));
        // 半点向上取整：100×0.845=84.5→85（Math.round 口径）
        assertEquals(85.0F, CdgFluctuationMath.fluctuatedSpeed(100.0F, 0.845F));
    }

    @Test
    void neverExceedsRatedAcrossTheWholeFluctuationRange() {
        // 系数域 [0.8, 1.0) 全程扫描（含紧邻 1.0）：整数额定下取整不超额定
        for (float rated : new float[]{16.0F, 96.0F, 128.0F, 256.0F, 512.0F}) {
            for (float factor = 0.8F; factor < 1.0F; factor += 0.0005F) {
                float out = CdgFluctuationMath.fluctuatedSpeed(rated, factor);
                assertTrue(out <= rated, "rated=" + rated + " factor=" + factor + " out=" + out);
                assertTrue(out == Math.rint(out), "output must be whole rpm: " + out);
            }
        }
    }
}
