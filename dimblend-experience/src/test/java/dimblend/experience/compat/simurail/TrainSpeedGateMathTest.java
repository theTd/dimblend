package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** E7' / E3 速度门槛判定（纯函数，见 {@link TrainSpeedGateMath}）。 */
class TrainSpeedGateMathTest {

    @Test
    void speedGateIsStrictlyGreaterThanFour() {
        assertFalse(TrainSpeedGateMath.isFast(4.0D));
        assertFalse(TrainSpeedGateMath.isFast(3.99D));
        assertTrue(TrainSpeedGateMath.isFast(4.01D));
        assertFalse(TrainSpeedGateMath.isBrakeAudible(0.0D));
        assertTrue(TrainSpeedGateMath.isBrakeAudible(10.0D));
    }
}
