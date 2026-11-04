package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.WarpGateMotion.Action;
import dimblend.api.WarpGateMotion.Side;
import org.junit.jupiter.api.Test;

/** 折跃门对 sable 子层级的速度与放行口径（用户 2026-11-03，2026-11-04 更正）。 */
class WarpGateMotionTest {

    private static final int SLAB = 100;

    @Test
    void aSlowSubLevelIsPushedUpToOneMetrePerSecondTowardsPlusX() {
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.0, Side.WEST, true),
                "静止的也要被推向 x+");
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.5, Side.WEST, true));
        assertEquals(1.0, WarpGateMotion.plusXDelta(0.0), 1.0E-9);
        assertEquals(0.5, WarpGateMotion.plusXDelta(0.5), 1.0E-9);
    }

    @Test
    void aSubLevelAlreadyFasterThanTheMinimumGetsNoExtraSpeed() {
        assertEquals(Action.NONE, WarpGateMotion.decide(1.5, Side.WEST, true));
        assertEquals(0.0, WarpGateMotion.plusXDelta(1.5), 1.0E-9);
        assertEquals(Action.NONE, WarpGateMotion.decide(1.0, Side.WEST, true),
                "正好 1 m/s 已经满足「至少 1」");
        assertEquals(0.0, WarpGateMotion.plusXDelta(1.0), 1.0E-9);
        assertEquals(1.0, WarpGateMotion.MIN_PLUS_X_SPEED, 1.0E-9,
                "2026-11-04：最低速度从 2 m/s 降到 1 m/s");
    }

    @Test
    void sidewaysDriftDoesNotCountAsSpeedTowardsPlusX() {
        // "朝向 x+ 方向有至少 1m/s 的速度" is about the +X component. A sub-level drifting
        // sideways with almost no forward speed still needs the push, or a sideways drift would
        // silently switch the gate's whole purpose off.
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.4, Side.WEST, true));
        assertEquals(0.6, WarpGateMotion.plusXDelta(0.4), 1.0E-9);
    }

    @Test
    void aSubLevelAlreadyReleasedTowardsPlusXIsNeverLetBackIn() {
        assertEquals(Action.BLOCK_REVERSE, WarpGateMotion.decide(-0.5, Side.EAST, true),
                "从 x+ 侧往 x- 走：拒绝");
        assertEquals(Action.NONE, WarpGateMotion.decide(0.5, Side.EAST, true),
                "还朝 x+ 走：放它离开，不再推进");
        assertEquals(Action.NONE, WarpGateMotion.decide(0.0, Side.EAST, true),
                "停在门里：不推进也不拦");
    }

    @Test
    void crossingForwardInBothDirectionsIsRefusedWhileTheCrewIsOffTheTrain() {
        assertEquals(Action.BLOCK_NO_CREW, WarpGateMotion.decide(3.0, Side.WEST, false),
                "有人在车外：正穿的也不许过，哪怕本来够快");
        assertEquals(Action.BLOCK_NO_CREW, WarpGateMotion.decide(0.0, Side.WEST, false));
        assertFalse(WarpGateMotion.decide(3.0, Side.WEST, false) == Action.NONE,
                "够快也不能替代全员上车的条件");
    }

    @Test
    void theReverseRefusalSurvivesWhenTheCrewIsOutside() {
        // A blocked reverse must not turn into a forward push just because someone stepped off:
        // the crew rule refuses crossings, it never opens one.
        assertEquals(Action.BLOCK_REVERSE, WarpGateMotion.decide(-2.0, Side.EAST, false));
    }

    @Test
    void theReverseCorrectionPushesTheBodyEastOfTheGateFace() {
        assertEquals(0.0, WarpGateMotion.reverseCorrection(101.0, SLAB), 1.0E-9,
                "西面正好贴在门东面：不用推");
        assertEquals(0.6, WarpGateMotion.reverseCorrection(100.4, SLAB), 1.0E-9);
        assertEquals(3.0, WarpGateMotion.reverseCorrection(98.0, SLAB), 1.0E-9,
                "整段已经穿到位姿西侧三格：推回去三格");
        assertEquals(0.0, WarpGateMotion.reverseCorrection(104.0, SLAB), 1.0E-9,
                "本来就在门东侧之外");
    }

    @Test
    void theCrewCorrectionPushesTheBodyWestOfTheGateFace() {
        assertEquals(0.0, WarpGateMotion.crewCorrection(100.0, SLAB), 1.0E-9,
                "东面正好贴在门西面：不用推");
        assertEquals(0.4, WarpGateMotion.crewCorrection(100.4, SLAB), 1.0E-9);
        assertEquals(2.5, WarpGateMotion.crewCorrection(102.5, SLAB), 1.0E-9);
        assertEquals(0.0, WarpGateMotion.crewCorrection(96.0, SLAB), 1.0E-9,
                "本来就在门西侧之外");
    }

    @Test
    void aStraddlingBoxKeepsTheSideItWasRememberedOn() {
        assertEquals(Side.EAST, WarpGateMotion.sideOf(101.2, 108.0, SLAB, Side.WEST),
                "整体已在东侧：改判东");
        assertEquals(Side.WEST, WarpGateMotion.sideOf(92.0, 99.8, SLAB, Side.EAST),
                "整体已在西侧：改判西");
        assertEquals(Side.EAST, WarpGateMotion.sideOf(99.5, 101.5, SLAB, Side.EAST),
                "正在跨门：保留上一刻的来向");
        assertEquals(Side.WEST, WarpGateMotion.sideOf(99.5, 101.5, SLAB, Side.WEST));
    }

    @Test
    void aBoxMetInsideTheGateTakesTheSideOfItsCentre() {
        assertEquals(Side.WEST, WarpGateMotion.sideOfCentre(100.2, SLAB));
        assertEquals(Side.EAST, WarpGateMotion.sideOfCentre(101.0, SLAB));
        assertEquals(Side.EAST, WarpGateMotion.sideOfCentre(103.4, SLAB));
    }

    @Test
    void theSlabOverlapMatchesABox() {
        assertTrue(WarpGateMotion.overlapsSlab(SLAB, 99.0, 101.0), "a box spanning the slab overlaps");
        assertTrue(WarpGateMotion.overlapsSlab(SLAB, 99.5, 100.2), "touching the near face overlaps");
        assertFalse(WarpGateMotion.overlapsSlab(SLAB, 97.0, 99.9), "entirely west of the slab");
        assertFalse(WarpGateMotion.overlapsSlab(SLAB, 101.1, 104.0), "entirely east of the slab");
    }
}
