package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 全局爬梯口径（2026-11-25 LOCKED #1 {@code ladder_global}）。
 */
class LadderClimbRulesTest {

    @Test
    void playerMustHoldJumpToClimb() {
        assertTrue(LadderClimbRules.climbPositionActive(true, true, true));
        assertFalse(LadderClimbRules.climbPositionActive(true, true, false),
                "玩家没按跳跃时不能再自动上爬");
    }

    @Test
    void playerNotOnLadderStaysInactive() {
        assertFalse(LadderClimbRules.climbPositionActive(false, true, true));
        assertFalse(LadderClimbRules.climbPositionActive(false, true, false));
    }

    @Test
    void mobsKeepVanillaClimbingRegardlessOfJumping() {
        assertTrue(LadderClimbRules.climbPositionActive(true, false, false),
                "僵尸等非玩家生物的寻路爬梯必须保持原版");
        assertTrue(LadderClimbRules.climbPositionActive(true, false, true));
        assertFalse(LadderClimbRules.climbPositionActive(false, false, true));
    }

    @Test
    void upwardsBoostStaysVanilla() {
        assertTrue(LadderClimbRules.CLIMB_UP_SPEED == 0.2D,
                "上爬速度是原版 0.2，改它不属于本条口径");
    }
}
