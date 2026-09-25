package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 孤立源水阈值（纯函数，见 {@link IsolatedWaterMath}）：<2 降级，≥2 保留。 */
class IsolatedWaterRulesTest {

    @Test
    void downgradesWithFewerThanTwoSourceNeighbors() {
        assertFalse(IsolatedWaterMath.keepSource(0));
        assertFalse(IsolatedWaterMath.keepSource(1));
    }

    @Test
    void keepsSourceWithTwoOrMoreSourceNeighbors() {
        assertTrue(IsolatedWaterMath.keepSource(2));
        assertTrue(IsolatedWaterMath.keepSource(3));
        assertTrue(IsolatedWaterMath.keepSource(4));
    }
}
