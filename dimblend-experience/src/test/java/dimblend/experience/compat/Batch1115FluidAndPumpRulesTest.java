package dimblend.experience.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.chassis.ChassisPreset;
import dimblend.experience.compat.camerapture.CameraFilmRules;
import dimblend.experience.compat.camerapture.PhotoOriginHud;
import dimblend.experience.compat.dragonsplus.FluidHatchRules;
import dimblend.experience.compat.fluid.CookeryCopperTapSupport;
import dimblend.experience.water.SurvivalWaterlogRules;
import org.junit.jupiter.api.Test;

class Batch1115FluidAndPumpRulesTest {

    @Test
    void fluidHatchLockedModeIsDepositOnly() {
        assertEquals(FluidHatchRules.Mode.DEPOSIT_ONLY, FluidHatchRules.ACTIVE_MODE);
        assertTrue(FluidHatchRules.allowsDeposit());
        assertFalse(FluidHatchRules.allowsExtract());

        assertTrue(FluidHatchRules.allowsExtract(FluidHatchRules.Mode.EXTRACT_ONLY));
        assertFalse(FluidHatchRules.allowsDeposit(FluidHatchRules.Mode.EXTRACT_ONLY));
    }

    @Test
    void lockedBatch1115DecisionsMatchSpec() {
        assertEquals(FluidHatchRules.Mode.DEPOSIT_ONLY, FluidHatchRules.ACTIVE_MODE);

        assertFalse(ChassisPreset.POWER.permitted(false),
                "controls-debug-menu: survival must not open debug spawn menu");
        assertFalse(PhotoOriginHud.GUIDE_WITH_ARROW_AND_DISTANCE,
                "photo-marker-hud: @ world marker only");
        assertFalse(CameraFilmRules.CREATIVE_BYPASSES_DURABILITY,
                "camera-creative-durability: creative uses camera film durability");
    }

    @Test
    void cookeryTapEligibilityRequiresEmptyOpenVessel() {
        assertTrue(CookeryCopperTapSupport.shouldFillStockpot(false, 0));
        assertFalse(CookeryCopperTapSupport.shouldFillStockpot(true, 0), "盖盖子的汤锅不可注水");
        assertFalse(CookeryCopperTapSupport.shouldFillStockpot(false, 1), "已有汤底的汤锅不可重复注水");

        assertTrue(CookeryCopperTapSupport.shouldFillTeapot(0, true));
        assertFalse(CookeryCopperTapSupport.shouldFillTeapot(0, false), "已有液体的水壶不可重复注水");
        assertFalse(CookeryCopperTapSupport.shouldFillTeapot(1, true), "加工中的水壶不可注水");
    }

    @Test
    void survivalWaterlogBlockedOnlyWithoutInfiniteMaterials() {
        assertTrue(SurvivalWaterlogRules.shouldBlockWaterlog(false, true, true));
        assertFalse(SurvivalWaterlogRules.shouldBlockWaterlog(true, true, true), "创造模式允许正常含水");
        assertFalse(SurvivalWaterlogRules.shouldBlockWaterlog(false, false, true), "岩浆桶/空桶不受含水拦截");
        assertFalse(SurvivalWaterlogRules.shouldBlockWaterlog(false, true, false), "普通不可含水方块不受拦截");
    }
}
