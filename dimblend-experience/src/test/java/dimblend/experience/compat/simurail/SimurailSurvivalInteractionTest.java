package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dimblend.experience.compat.simurail.SimurailSurvivalInteraction.Decision;
import org.junit.jupiter.api.Test;

/**
 * 生存模式右键 Simurail 方块的口径（用户 2026-11-03：扳手充能要能在生存用）。
 *
 * <p>关键不变量：放行"扳手充能"不能顺带放行车钩换类型，也不能放开别的 Simurail 方块。
 */
class SimurailSurvivalInteractionTest {

    @Test
    void aCreateWrenchOnACouplerIsAllowedInSurvival() {
        assertEquals(Decision.ALLOW, SimurailSurvivalInteraction.decide(false, true, true, false),
                "空手或别的物品不该被这个改动放行，只有扳手走这条");
    }

    @Test
    void aBareHandOnACouplerStaysDenied() {
        assertEquals(Decision.DENY, SimurailSurvivalInteraction.decide(false, true, false, false),
                "空手右键会改车钩长短，不在本条口径里");
    }

    @Test
    void theBroadSwitchStillOpensEveryCouplerInteraction() {
        assertEquals(Decision.ALLOW, SimurailSurvivalInteraction.decide(false, true, false, true),
                "allowSurvivalCouplerInteraction 打开时保持原行为");
    }

    @Test
    void theBogeyKeepsItsBlockOnlyPass() {
        assertEquals(Decision.PASS_BLOCK_ONLY, SimurailSurvivalInteraction.decide(true, false, false, false));
        assertEquals(Decision.PASS_BLOCK_ONLY, SimurailSurvivalInteraction.decide(true, false, true, false),
                "潜行扳手不能把转向架捡走，所以物品钩子始终关掉");
    }

    @Test
    void everyOtherSimurailBlockStaysDenied() {
        assertEquals(Decision.DENY, SimurailSurvivalInteraction.decide(false, false, true, false),
                "本次只开车钩充能，别的 Simurail 方块不动");
        assertEquals(Decision.DENY, SimurailSurvivalInteraction.decide(false, false, false, true),
                "即使宽开关打开，非车钩方块也不放行");
    }
}