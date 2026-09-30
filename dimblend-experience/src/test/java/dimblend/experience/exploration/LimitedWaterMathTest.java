package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LimitedWaterMathTest {

    @Test
    void checkedWriteKeepsSourceOnlyWithTwoOrMoreNeighbours() {
        assertFalse(LimitedWaterMath.keepSource(0, false));
        assertFalse(LimitedWaterMath.keepSource(1, false));
        assertTrue(LimitedWaterMath.keepSource(2, false));
        assertTrue(LimitedWaterMath.keepSource(4, false));
    }

    @Test
    void creativeDeciderSkipsTheCheck() {
        assertTrue(LimitedWaterMath.keepSource(0, true));
        assertTrue(LimitedWaterMath.keepSource(1, true));
    }

    @Test
    void deciderRadiusIsEightBlocksInclusive() {
        assertTrue(LimitedWaterMath.withinDeciderRadius(0));
        assertTrue(LimitedWaterMath.withinDeciderRadius(64.0));
        assertFalse(LimitedWaterMath.withinDeciderRadius(64.0001));
    }
}
