package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** I20 刹车车厢的红石上限例外与建车电路特征（纯函数，见 {@link ChaseBrakeSignalRules}）。 */
class ChaseBrakeSignalRulesTest {

    private static final double EPSILON = 1.0E-9D;

    @Test
    void ordinaryBogeyIsStillCappedToTheConfiguredRedstoneLevel() {
        double capOne = 1.0D / 15.0D;
        assertEquals(capOne, ChaseBrakeSignalRules.applyCap(1.0D, 1, false), EPSILON);
        assertEquals(capOne, ChaseBrakeSignalRules.applyCap(7.0D / 15.0D, 1, false), EPSILON);
        assertEquals(capOne, ChaseBrakeSignalRules.applyCap(15.0D / 15.0D, 1, false), EPSILON);
        // 上限 15 = 关闭（等效原版线性）
        assertEquals(7.0D / 15.0D, ChaseBrakeSignalRules.applyCap(7.0D / 15.0D, 15, false), EPSILON);
    }

    @Test
    void chaseBrakeCarGetsFullBrakeForAnyPositiveSignal() {
        assertEquals(ChaseBrakeSignalRules.FULL_BRAKE,
                ChaseBrakeSignalRules.applyCap(1.0D, 1, true), EPSILON,
                "红石块 15 级 -> 满刹，不跟随 trainBrakeSignalCap=1");
        assertEquals(ChaseBrakeSignalRules.FULL_BRAKE,
                ChaseBrakeSignalRules.applyCap(1.0D / 15.0D, 1, true), EPSILON,
                "任意正信号（1 级）也是满刹：刹车车厢是固定制动力，不随信号线性");
        assertEquals(1.0D, ChaseBrakeSignalRules.FULL_BRAKE, EPSILON);
    }

    @Test
    void zeroSignalStaysZeroEvenForTheChaseBrakeCar() {
        assertEquals(0.0D, ChaseBrakeSignalRules.applyCap(0.0D, 1, true), EPSILON);
        assertEquals(0.0D, ChaseBrakeSignalRules.applyCap(0.0D, 1, false), EPSILON);
        assertTrue(ChaseBrakeSignalRules.applyCap(-0.0D, 1, true) <= 0.0D);
    }

    @Test
    void onlyTheRedstoneBlockPlusLampCircuitIsABrakeCar() {
        assertTrue(ChaseBrakeSignalRules.isChaseBrakeCircuit(true, true));
        assertFalse(ChaseBrakeSignalRules.isChaseBrakeCircuit(true, false),
                "单红石块是普通转向架的正常手动通电，仍受上限约束");
        assertFalse(ChaseBrakeSignalRules.isChaseBrakeCircuit(false, true));
        assertFalse(ChaseBrakeSignalRules.isChaseBrakeCircuit(false, false));
    }
}
