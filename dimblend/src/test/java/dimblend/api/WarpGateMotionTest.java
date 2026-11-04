package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.WarpGateMotion.Action;
import dimblend.api.WarpGateMotion.Side;
import org.junit.jupiter.api.Test;

/**
 * 折跃门对 sable 子层级的速度与放行口径：用户 2026-11-03 原始需求 + 2026-11-04 两次更正。
 */
class WarpGateMotionTest {

    private static final int SLAB = 100;
    private static final boolean CREW_ABOARD = true;
    private static final boolean CREW_SPLIT = false;
    private static final boolean RIDDEN = true;
    private static final boolean EMPTY = false;

    @Test
    void aSlowRiddenSubLevelIsPushedUpToOneMetrePerSecondTowardsPlusX() {
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.0, Side.WEST, CREW_ABOARD, RIDDEN),
                "静止的也要被推向 x+");
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.5, Side.WEST, CREW_ABOARD, RIDDEN));
        assertEquals(1.0, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 0.0), 1.0E-9);
        assertEquals(0.5, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 0.5), 1.0E-9);
    }

    @Test
    void aSubLevelNobodyRidesIsNotPushed() {
        // 2026-11-04 补充条件：「在其上有 任意游戏模式 玩家 时」才给最低速度。
        assertEquals(Action.NONE, WarpGateMotion.decide(0.0, Side.WEST, CREW_ABOARD, EMPTY));
        assertEquals(0.0, WarpGateMotion.deltaX(Action.NONE, 0.0), 1.0E-9);
    }

    @Test
    void aSubLevelAlreadyFasterThanTheMinimumGetsNoExtraSpeed() {
        assertEquals(Action.NONE, WarpGateMotion.decide(1.5, Side.WEST, CREW_ABOARD, RIDDEN));
        assertEquals(Action.NONE, WarpGateMotion.decide(1.0, Side.WEST, CREW_ABOARD, RIDDEN),
                "正好 1 m/s 已经满足「至少 1」");
        assertEquals(1.0, WarpGateMotion.MIN_PLUS_X_SPEED, 1.0E-9,
                "2026-11-04：最低速度从 2 m/s 降到 1 m/s");
    }

    @Test
    void sidewaysDriftDoesNotCountAsSpeedTowardsPlusX() {
        // 「朝向 x+ 方向有至少 1m/s 的速度」说的是 X 分量；只看向量长度会让侧向漂移把闸门关掉。
        assertEquals(Action.PUSH_PLUS_X, WarpGateMotion.decide(0.4, Side.WEST, CREW_ABOARD, RIDDEN));
        assertEquals(0.6, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 0.4), 1.0E-9);
    }

    @Test
    void aSubLevelComingBackFromPlusXIsTurnedAroundAtOneMetrePerSecond() {
        // 2026-11-04 补充：「阻止」从硬性阻止改成「速度强制为 +1m/s」，避免碰撞箱出问题。
        assertEquals(Action.TURN_PLUS_X, WarpGateMotion.decide(-0.5, Side.EAST, CREW_ABOARD, RIDDEN));
        assertEquals(1.0, -0.5 + WarpGateMotion.deltaX(Action.TURN_PLUS_X, -0.5), 1.0E-9,
                "退回来的结构被推到正好 +1 m/s");
        assertEquals(1.0, -7.0 + WarpGateMotion.deltaX(Action.TURN_PLUS_X, -7.0), 1.0E-9,
                "退得越猛推回的冲量越大，结果仍是正好 +1");
        assertEquals(Action.NONE, WarpGateMotion.decide(0.5, Side.EAST, CREW_ABOARD, RIDDEN),
                "还朝 x+ 走：放它离开，不再推进");
        assertEquals(Action.NONE, WarpGateMotion.decide(0.0, Side.EAST, CREW_ABOARD, RIDDEN));
    }

    @Test
    void aForwardCrossingIsHeldStillWhileASurvivalPlayerIsOffTheTrain() {
        // 2026-11-04 补充：原本是「不允许任何 sable 穿过」，改为「使其中的 sable 速度维持 0」。
        assertEquals(Action.HOLD_STILL, WarpGateMotion.decide(5.0, Side.WEST, CREW_SPLIT, RIDDEN),
                "再快也要按住");
        assertEquals(Action.HOLD_STILL, WarpGateMotion.decide(0.0, Side.WEST, CREW_SPLIT, EMPTY));
        assertEquals(-5.0, WarpGateMotion.deltaX(Action.HOLD_STILL, 5.0), 1.0E-9);
        assertEquals(2.0, WarpGateMotion.deltaX(Action.HOLD_STILL, -2.0), 1.0E-9,
                "反着走的也归零");
    }

    @Test
    void turningBackWinsOverHoldingSoAReleasedStructureIsNeverTrappedInside() {
        // 已经在 x+ 侧外的结构不是「正在穿过」，按住它只会把它困在门里；先用转身规则把它推开。
        assertEquals(Action.TURN_PLUS_X, WarpGateMotion.decide(-2.0, Side.EAST, CREW_SPLIT, RIDDEN));
        assertEquals(Action.NONE, WarpGateMotion.decide(2.0, Side.EAST, CREW_SPLIT, EMPTY));
    }

    @Test
    void onlyTheTwoForwardRulesHaveADeltaAndTheHeldOneGoesToZero() {
        assertEquals(0.0, WarpGateMotion.deltaX(Action.NONE, 3.0), 1.0E-9);
        assertEquals(0.0, WarpGateMotion.deltaX(Action.PUSH_PLUS_X, 1.0), 1.0E-9,
                "已经到位的推没有增量");
        assertEquals(0.0, WarpGateMotion.deltaX(Action.HOLD_STILL, 0.0), 1.0E-9,
                "本来就静止：按住也没有增量");
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
