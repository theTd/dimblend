package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** E7 / E3 速度门槛与半径判定（纯函数，见 {@link TrainOffStructureMath}）。 */
class TrainOffStructureMathTest {

    @Test
    void speedGateIsStrictlyGreaterThanFour() {
        assertFalse(TrainOffStructureMath.isFast(4.0D));
        assertFalse(TrainOffStructureMath.isFast(3.99D));
        assertTrue(TrainOffStructureMath.isFast(4.01D));
        assertFalse(TrainOffStructureMath.isBrakeAudible(0.0D));
        assertTrue(TrainOffStructureMath.isBrakeAudible(10.0D));
    }

    @Test
    void teleportOnlyWhenFastAndOffStructureAndOffCorridor() {
        assertFalse(TrainOffStructureMath.shouldTeleport(false, false, false));
        assertFalse(TrainOffStructureMath.shouldTeleport(false, true, false));
        assertFalse(TrainOffStructureMath.shouldTeleport(true, true, false));
        assertTrue(TrainOffStructureMath.shouldTeleport(true, false, false));
    }

    @Test
    void corridorLandingIsStableWhenGateStillOpen() {
        // 无床落到 A2 走廊（z=5）后，闸门仍开、附近无 sable，下一周期不得再传
        assertTrue(TrainOffStructureMath.isInCorridorSafeBand(5.0D));
        assertTrue(TrainOffStructureMath.isInCorridorSafeBand(-16.0D));
        assertTrue(TrainOffStructureMath.isInCorridorSafeBand(16.0D));
        assertFalse(TrainOffStructureMath.isInCorridorSafeBand(16.01D));
        assertFalse(TrainOffStructureMath.shouldTeleport(true, false, true));
    }

    @Test
    void pointInsideAabbIsWithinRadius() {
        assertTrue(TrainOffStructureMath.isWithinRadius(
                0, 0, 0, -1, -1, -1, 1, 1, 1, 32));
    }

    @Test
    void pointOnRadiusBoundaryCountsAsInside() {
        assertTrue(TrainOffStructureMath.isWithinRadius(
                32, 0, 0, 0, 0, 0, 0, 0, 0, 32));
        assertFalse(TrainOffStructureMath.isWithinRadius(
                32.01, 0, 0, 0, 0, 0, 0, 0, 0, 32));
    }

    @Test
    void cubeCornerBeyondSphereIsOutside() {
        // 立方体半边 32 的角点欧氏距离 32√3 ≈ 55.4，半径判定应收成球体
        assertFalse(TrainOffStructureMath.isWithinRadius(
                32, 32, 32, 0, 0, 0, 0, 0, 0, 32));
    }
}
