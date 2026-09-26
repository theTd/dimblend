package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimurailBogeyLockRulesTest {
    /** Vanilla's release sentinel: {@code (double) Float.MAX_VALUE}. */
    private static final double VANILLA_MAX = 3.4028234663852886E38;

    @Test
    void settledAxleIsPinnedToTheRail() {
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -0.0, 0.0));
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -0.0, 0.2));
    }

    @Test
    void settledAxleStaysPinnedWhenVanillaReleases() {
        // Lateral/crest overspeed release and allowVerticalMovement free lift all widen the bound.
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -VANILLA_MAX, 0.0));
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -VANILLA_MAX, -0.1));
    }

    @Test
    void settlingAxleKeepsVanillaSqueeze() {
        assertEquals(0.25, SimurailBogeyLockRules.captiveHalfWidth(false, -0.25, 0.3));
        assertEquals(0.25, SimurailBogeyLockRules.captiveHalfWidth(false, -0.25, -0.3));
    }

    @Test
    void releasedSettlingAxleIsHeldWhereItIs() {
        assertEquals(0.3, SimurailBogeyLockRules.captiveHalfWidth(false, -VANILLA_MAX, 0.3));
        assertEquals(0.3, SimurailBogeyLockRules.captiveHalfWidth(false, -VANILLA_MAX, -0.3));
    }

    @Test
    void captiveLimitNeverExceedsCurrentOffset() {
        assertEquals(0.1, SimurailBogeyLockRules.captiveHalfWidth(false, -0.4, 0.1));
    }

    @Test
    void degenerateInputsCollapseToPinned() {
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(false, 0.0, 0.0));
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(false, -VANILLA_MAX, Double.NaN));
        assertTrue(SimurailBogeyLockRules.captiveHalfWidth(false, 0.1, 0.2) == 0.0,
                "a positive lower bound is never a valid squeeze");
    }
}
