package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** E3 刹车音效可配置门槛判定（纯函数，见 {@link TrainSpeedGateMath}）。 */
class TrainSpeedGateMathTest {

    @Test
    void brakeSoundGateUsesItsOwnStrictThreshold() {
        assertFalse(TrainSpeedGateMath.isBrakeAudible(1.99D, 2.0D));
        assertFalse(TrainSpeedGateMath.isBrakeAudible(2.0D, 2.0D));
        assertTrue(TrainSpeedGateMath.isBrakeAudible(2.01D, 2.0D));
        assertFalse(TrainSpeedGateMath.isBrakeAudible(3.0D, 4.0D));
        assertTrue(TrainSpeedGateMath.isBrakeAudible(0.1D, 0.0D));
    }
    @Test
    void emergencyGateRemainsFourIndependentlyOfBrakeSoundThreshold() {
        assertFalse(TrainSpeedGateMath.isFast(4.0D));
        assertFalse(TrainSpeedGateMath.isFast(3.99D));
        assertTrue(TrainSpeedGateMath.isFast(4.01D));
        assertTrue(TrainSpeedGateMath.isBrakeAudible(3.0D, 2.0D));
        assertFalse(TrainSpeedGateMath.isFast(3.0D));
    }

    @Test
    void onlyTheDeadmanAnnouncementIsPitchedDown() {
        assertEquals(0.5F, TrainSpeedGateMath.brakePitch(true), 1.0E-6F,
                "2026-11-04：急刹播报降到 0.5");
        assertEquals(1.0F, TrainSpeedGateMath.brakePitch(false), 1.0E-6F,
                "司机自己施闸不受这条改动影响");
        assertEquals(0.5F, TrainSpeedGateMath.EMERGENCY_BRAKE_PITCH, 1.0E-6F);
        assertEquals(1.0F, TrainSpeedGateMath.NORMAL_BRAKE_PITCH, 1.0E-6F);
    }
}
