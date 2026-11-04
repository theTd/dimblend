package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 折跃门对 sable 子层级的速度口径（用户 2026-11-03）。 */
class WarpGateMotionTest {

    @Test
    void aSlowSubLevelIsPushedUpToTwoMetresPerSecondTowardsPlusX() {
        assertEquals(WarpGateMotion.Action.PUSH_PLUS_X,
                WarpGateMotion.decide(0.0, 0.0, 0.0, false), "静止的也要被推向 x+");
        assertEquals(WarpGateMotion.Action.PUSH_PLUS_X,
                WarpGateMotion.decide(1.5, 0.0, 0.0, false));
        assertEquals(2.0, WarpGateMotion.plusXDelta(0.0), 1.0E-9);
        assertEquals(0.5, WarpGateMotion.plusXDelta(1.5), 1.0E-9);
    }

    @Test
    void aSubLevelAlreadyFasterThanTwoGetsNoExtraSpeed() {
        assertEquals(WarpGateMotion.Action.NONE, WarpGateMotion.decide(2.5, 0.0, 0.0, false));
        assertEquals(0.0, WarpGateMotion.plusXDelta(2.5), 1.0E-9);
        assertEquals(WarpGateMotion.Action.NONE, WarpGateMotion.decide(2.0, 0.0, 0.0, false),
                "正好 2 m/s 已经满足「至少 2」");
        assertEquals(0.0, WarpGateMotion.plusXDelta(2.0), 1.0E-9);
    }

    @Test
    void sidewaysDriftDoesNotCountAsSpeedTowardsPlusX() {
        // "朝向 x+ 方向有至少 2m/s 的速度" is about the +X component. A sub-level drifting at
        // 1.8 m/s sideways with only 1.0 m/s forward still needs the push, or a sideways drift
        // would silently switch the gate's whole purpose off.
        assertEquals(WarpGateMotion.Action.PUSH_PLUS_X, WarpGateMotion.decide(1.0, 0.0, 1.8, false));
        assertEquals(1.0, WarpGateMotion.plusXDelta(1.0), 1.0E-9);
    }

    @Test
    void crossingBackwardsIsBlocked() {
        assertEquals(WarpGateMotion.Action.BLOCK_NEGATIVE_X,
                WarpGateMotion.decide(-0.5, 0.0, 0.0, true));
        assertFalse(WarpGateMotion.decide(-0.5, 0.0, 0.0, false) == WarpGateMotion.Action.BLOCK_NEGATIVE_X,
                "not approaching from the +X side: the block rule does not apply");
    }

    @Test
    void theSlabOverlapMatchesABox() {
        assertTrue(WarpGateMotion.overlapsSlab(100, 99.0, 101.0), "a box spanning the slab overlaps");
        assertTrue(WarpGateMotion.overlapsSlab(100, 99.5, 100.2), "touching the near face overlaps");
        assertFalse(WarpGateMotion.overlapsSlab(100, 97.0, 99.9), "entirely west of the slab");
        assertFalse(WarpGateMotion.overlapsSlab(100, 101.1, 104.0), "entirely east of the slab");
    }
}