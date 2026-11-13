package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.WarpGateMotion.Action;
import dimblend.api.WarpGateMotion.Side;
import org.junit.jupiter.api.Test;

/**
 * 折跃门对 sable 子层级的速度与放行口径：2026-11-12 更新为「任意一个玩家在任意 sable 上即生效」。
 */
class WarpGateMotionTest {

    private static final int SLAB = 100;
    private static final boolean ANY_PLAYER_ON_ANY_SABLE = true;
    private static final boolean NO_PLAYER_ON_ANY_SABLE = false;

    @Test
    void anyPlayerOnAnySablePushesEverySlowCrossingSubLevelToOneMetrePerSecond() {
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.0, Side.WEST, ANY_PLAYER_ON_ANY_SABLE),
                "任意玩家在任意 sable 上时，静止的子世界（含无人挂车）也要被推向 x+");
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.5, Side.WEST, ANY_PLAYER_ON_ANY_SABLE));
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(-0.2, Side.WEST, ANY_PLAYER_ON_ANY_SABLE));
        assertEquals(1.0, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 0.0), 1.0E-9);
        assertEquals(0.5, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 0.5), 1.0E-9);
        assertEquals(1.2, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, -0.2), 1.0E-9);
    }

    @Test
    void aSubLevelAlreadyFasterThanTheMinimumGetsNoExtraSpeed() {
        assertEquals(Action.NONE, WarpGateMotion.decide(1.5, Side.WEST, ANY_PLAYER_ON_ANY_SABLE));
        assertEquals(Action.NONE, WarpGateMotion.decide(1.0, Side.WEST, ANY_PLAYER_ON_ANY_SABLE),
                "正好 1 m/s 已经满足「至少 1」");
        assertEquals(1.0, WarpGateMotion.MIN_PLUS_X_SPEED, 1.0E-9);
    }

    @Test
    void sidewaysDriftDoesNotCountAsSpeedTowardsPlusX() {
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.4, Side.WEST, ANY_PLAYER_ON_ANY_SABLE));
        assertEquals(0.6, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 0.4), 1.0E-9);
    }

    @Test
    void aSubLevelComingBackFromPlusXIsTurnedAroundAtOneMetrePerSecond() {
        assertEquals(Action.TURN_PLUS_X, WarpGateMotion.decide(-0.5, Side.EAST, ANY_PLAYER_ON_ANY_SABLE));
        assertEquals(1.0, -0.5 + WarpGateMotion.deltaX(Action.TURN_PLUS_X, -0.5), 1.0E-9,
                "退回来的结构被推到正好 +1 m/s");
        assertEquals(1.0, -7.0 + WarpGateMotion.deltaX(Action.TURN_PLUS_X, -7.0), 1.0E-9,
                "退得越猛推回的冲量越大，结果仍是正好 +1");
        assertEquals(Action.NONE, WarpGateMotion.decide(0.5, Side.EAST, ANY_PLAYER_ON_ANY_SABLE),
                "还朝 x+ 走：放它离开，不再推进");
        assertEquals(Action.NONE, WarpGateMotion.decide(0.0, Side.EAST, ANY_PLAYER_ON_ANY_SABLE));
    }

    @Test
    void aForwardCrossingIsHeldStillWhenZeroPlayersAreOnAnySable() {
        assertEquals(Action.HOLD_STILL, WarpGateMotion.decide(5.0, Side.WEST, NO_PLAYER_ON_ANY_SABLE),
                "全服没有任何玩家在任何 sable 上时，按住防空车溜走");
        assertEquals(Action.HOLD_STILL, WarpGateMotion.decide(0.0, Side.WEST, NO_PLAYER_ON_ANY_SABLE));
        assertEquals(-5.0, WarpGateMotion.deltaX(Action.HOLD_STILL, 5.0), 1.0E-9);
        assertEquals(2.0, WarpGateMotion.deltaX(Action.HOLD_STILL, -2.0), 1.0E-9);
    }

    @Test
    void turningBackWinsOverHoldingSoAReleasedStructureIsNeverTrappedInside() {
        assertEquals(Action.TURN_PLUS_X, WarpGateMotion.decide(-2.0, Side.EAST, NO_PLAYER_ON_ANY_SABLE));
        assertEquals(Action.NONE, WarpGateMotion.decide(2.0, Side.EAST, NO_PLAYER_ON_ANY_SABLE));
    }

    @Test
    void onlyTheTwoForwardRulesHaveADeltaAndTheHeldOneGoesToZero() {
        assertEquals(0.0, WarpGateMotion.deltaX(Action.NONE, 3.0), 1.0E-9);
        assertEquals(0.0, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 1.0), 1.0E-9);
        assertEquals(0.0, WarpGateMotion.deltaX(Action.HOLD_STILL, 0.0), 1.0E-9);
    }

    @Test
    void aStraddlingBoxKeepsTheSideItWasRememberedOn() {
        assertEquals(Side.EAST, WarpGateMotion.sideOf(101.2, 108.0, SLAB, Side.WEST));
        assertEquals(Side.WEST, WarpGateMotion.sideOf(92.0, 99.8, SLAB, Side.EAST));
        assertEquals(Side.EAST, WarpGateMotion.sideOf(99.5, 101.5, SLAB, Side.EAST));
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
        assertTrue(WarpGateMotion.overlapsSlab(SLAB, 99.0, 101.0));
        assertTrue(WarpGateMotion.overlapsSlab(SLAB, 99.5, 100.2));
        assertFalse(WarpGateMotion.overlapsSlab(SLAB, 97.0, 99.9));
        assertFalse(WarpGateMotion.overlapsSlab(SLAB, 101.1, 104.0));
    }
}
