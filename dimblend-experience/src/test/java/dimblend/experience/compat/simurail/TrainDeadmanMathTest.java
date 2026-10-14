package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** E7' 无人值守判定（玩家视角最近车架）与解除脉冲状态机（纯函数，见 {@link TrainDeadmanMath}）。 */
class TrainDeadmanMathTest {

    private static final double RADIUS = 32.0D;

    @Test
    void attendingWhenNearestBogeyFastAndWithinRadius() {
        TrainDeadmanMath.Probe fast = new TrainDeadmanMath.Probe(0, 64, 0, 10);
        assertTrue(TrainDeadmanMath.isAttending(20, 64, 0, List.of(fast), RADIUS));
    }

    @Test
    void notAttendingWhenNearestBogeyBeyondRadius() {
        TrainDeadmanMath.Probe fast = new TrainDeadmanMath.Probe(0, 64, 0, 10);
        assertFalse(TrainDeadmanMath.isAttending(32.01D, 64, 0, List.of(fast), RADIUS));
        assertTrue(TrainDeadmanMath.isAttending(32, 64, 0, List.of(fast), RADIUS));
    }

    @Test
    void parkedBogeyCannotProxyForDistantMovingTrain() {
        // 最近的是旁站停放的车（低速），远处行驶的车不能由它代管
        TrainDeadmanMath.Probe parked = new TrainDeadmanMath.Probe(0, 64, 0, 0);
        TrainDeadmanMath.Probe moving = new TrainDeadmanMath.Probe(20, 64, 0, 10);
        assertFalse(TrainDeadmanMath.isAttending(5, 64, 0, List.of(parked, moving), RADIUS));
    }

    @Test
    void slowNearestBlocksEvenWhenMovingBogeyAlsoInRadius() {
        // 停放车更近（5 格）且在半径内，行驶车（20 格）也在半径内——只看最近架，仍不算跟车
        TrainDeadmanMath.Probe parked = new TrainDeadmanMath.Probe(5, 64, 0, 0);
        TrainDeadmanMath.Probe moving = new TrainDeadmanMath.Probe(20, 64, 0, 10);
        assertFalse(TrainDeadmanMath.isAttending(0, 64, 0, List.of(parked, moving), RADIUS));
    }

    @Test
    void longConsistDriverAttendsNearestCar() {
        // 长编组稳态：司机在车尾，车尾车架同样超速，就近值守本节即有效
        TrainDeadmanMath.Probe head = new TrainDeadmanMath.Probe(0, 64, 0, 10);
        TrainDeadmanMath.Probe tail = new TrainDeadmanMath.Probe(60, 64, 0, 10);
        assertTrue(TrainDeadmanMath.isAttending(61, 64, 0, List.of(head, tail), RADIUS));
    }

    @Test
    void emptyMemberListIsNotAttending() {
        assertFalse(TrainDeadmanMath.isAttending(0, 64, 0, List.of(), RADIUS));
    }

    @Test
    void pulseReleasesOnlyAfterFullPulseWhileLatched() {
        // 锁存期间：通沿 arm → 断沿解除并复位
        var armed = TrainDeadmanMath.stepPulse(false, true, false, true);
        assertTrue(armed.armed());
        assertFalse(armed.release());
        var released = TrainDeadmanMath.stepPulse(true, false, true, true);
        assertFalse(released.armed());
        assertTrue(released.release());
    }

    @Test
    void signalAlreadyHighBeforeLatchDoesNotReleaseOnFall() {
        // 锁存前已常通：arm 为 false 进入锁存，仅断开不构成完整脉冲
        var step = TrainDeadmanMath.stepPulse(true, false, false, true);
        assertFalse(step.armed());
        assertFalse(step.release());
    }

    @Test
    void unlatchedAlwaysClearsArmAndNeverReleases() {
        // 未锁存维度逐 tick 清零 arm：锁存置位瞬间不可能携带残留 arm
        var fromArmed = TrainDeadmanMath.stepPulse(true, true, true, false);
        assertFalse(fromArmed.armed());
        assertFalse(fromArmed.release());
        var falling = TrainDeadmanMath.stepPulse(true, false, true, false);
        assertFalse(falling.armed());
        assertFalse(falling.release());
    }

    @Test
    void steadySignalKeepsStateWithoutRelease() {
        // 持续高位保持 arm 不解除；持续低位无动作
        var steadyHigh = TrainDeadmanMath.stepPulse(true, true, true, true);
        assertTrue(steadyHigh.armed());
        assertFalse(steadyHigh.release());
        var steadyLow = TrainDeadmanMath.stepPulse(false, false, false, true);
        assertFalse(steadyLow.armed());
        assertFalse(steadyLow.release());
    }
}
