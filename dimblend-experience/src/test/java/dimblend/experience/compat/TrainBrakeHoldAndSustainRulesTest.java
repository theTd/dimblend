package dimblend.experience.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.simurail.PhysicsBogeyBrakeHoldRules;
import dimblend.experience.compat.simurail.TrainBrakeSustainRules;
import org.junit.jupiter.api.Test;

/**
 * 制动保持 + 持续鸣响的口径（2026-11-25 LOCKED #2 / #4）。
 */
class TrainBrakeHoldAndSustainRulesTest {

    @Test
    void brakeHoldsStrictlyBelowOneMetrePerSecond() {
        assertTrue(PhysicsBogeyBrakeHoldRules.shouldHold(1.0D, false, 0.0D));
        assertTrue(PhysicsBogeyBrakeHoldRules.shouldHold(1.0D, false, 0.999D));
        assertTrue(PhysicsBogeyBrakeHoldRules.shouldHold(1.0D, false, -0.999D), "方向无关，只看绝对值");
        assertFalse(PhysicsBogeyBrakeHoldRules.shouldHold(1.0D, false, 1.0D));
        assertFalse(PhysicsBogeyBrakeHoldRules.shouldHold(1.0D, false, 3.5D));
    }

    @Test
    void noBrakeNoHold() {
        assertFalse(PhysicsBogeyBrakeHoldRules.shouldHold(0.0D, false, 0.0D));
    }

    @Test
    void exemptVehiclesNeverHold() {
        assertFalse(PhysicsBogeyBrakeHoldRules.shouldHold(1.0D, true, 0.2D));
        assertFalse(PhysicsBogeyBrakeHoldRules.shouldHold(1.0D, true, 0.0D));
    }

    @Test
    void sustainNeedsBrakeSpeedAndSounds() {
        assertTrue(TrainBrakeSustainRules.shouldSustain(1.0D, 5.0D, true));
        assertTrue(TrainBrakeSustainRules.shouldSustain(1.0D, 2.0D, true), "2 m/s 含在持续段");
        assertFalse(TrainBrakeSustainRules.shouldSustain(1.0D, 1.999D, true), "跌破 2 m/s 即停");
        assertFalse(TrainBrakeSustainRules.shouldSustain(0.0D, 5.0D, true));
        assertFalse(TrainBrakeSustainRules.shouldSustain(1.0D, 5.0D, false));
    }

    @Test
    void sustainVolumeAndReplayWindowMatchTheUserBrief() {
        assertTrue(TrainBrakeSustainRules.SUSTAIN_VOLUME == 0.75F);
        assertTrue(TrainBrakeSustainRules.FADE_BELOW_SPEED == 2.0D);
        assertTrue(TrainBrakeSustainRules.replayIntervalTicks() == 100,
                "100 tick = 5.00s，对齐 239823/48000≈4.996s 的采样长度");
    }
}
