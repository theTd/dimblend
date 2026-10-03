package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;

class PregenAdmissionTest {
    private static PregenAdmission.Reason healthy(PregenAdmission gate, long now) {
        return gate.update(now, 0.3, 0.5, true, false, 10, 12, 30, 3000);
    }

    @Test
    void requiresSustainedHeadroomBeforeFirstAdmission() {
        var gate = new PregenAdmission();
        assertEquals(PregenAdmission.Reason.RECOVERING, healthy(gate, 100));
        assertEquals(PregenAdmission.Reason.RECOVERING, healthy(gate, 3099));
        assertEquals(PregenAdmission.Reason.READY, healthy(gate, 3100));
    }

    @Test
    void cpuPressureStopsImmediatelyAndRestartsRecovery() {
        var gate = new PregenAdmission();
        healthy(gate, 0);
        healthy(gate, 3000);
        assertEquals(PregenAdmission.Reason.CPU,
                gate.update(3100, 0.5, 0.5, true, false, 10, 10, 30, 3000));
        assertEquals(PregenAdmission.Reason.RECOVERING, healthy(gate, 3200));
        assertEquals(PregenAdmission.Reason.RECOVERING, healthy(gate, 6199));
        assertEquals(PregenAdmission.Reason.READY, healthy(gate, 6200));
    }

    @Test
    void unknownCpuLoadFailsClosed() {
        for (double load : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertEquals(PregenAdmission.Reason.CPU,
                    new PregenAdmission().update(0, load, 0.5, true, false, 10, 10, 30, 3000));
        }
    }

    @Test
    void clientPressureStopsDespiteFastServerTicks() {
        assertEquals(PregenAdmission.Reason.CLIENT,
                new PregenAdmission().update(0, 0.2, 0.5, false, false, 5, 5, 30, 3000));
    }

    @Test
    void latestSlowTickStopsDespiteHealthyAverage() {
        assertEquals(PregenAdmission.Reason.TICKS,
                new PregenAdmission().update(0, 0.2, 0.5, true, false, 10, 45, 30, 3000));
        assertEquals(PregenAdmission.Reason.TICKS,
                new PregenAdmission().update(0, 0.2, 0.5, true, false, 35, 10, 30, 3000));
    }

    @Test
    void busyPoolStopsEvenWithNoQueuedSubmissions() {
        assertTrue(PregenAdmission.poolBusy(15, 15, 0, 0));
        assertTrue(PregenAdmission.poolBusy(15, 8, 0, 0));
        assertTrue(PregenAdmission.poolBusy(15, 1, 0, 15));
        assertTrue(PregenAdmission.poolBusy(15, 1, 8, 7));
        assertFalse(PregenAdmission.poolBusy(15, 2, 0, 0));
        assertEquals(PregenAdmission.Reason.POOL,
                new PregenAdmission().update(0, 0.2, 0.5, true, true, 10, 10, 30, 3000));
    }

    @Test
    void foreignDemandRequiresRecoveryAfterItClears() {
        var gate = new PregenAdmission();
        healthy(gate, 0);
        healthy(gate, 3000);
        gate.block(PregenAdmission.Reason.FOREIGN);
        assertEquals(PregenAdmission.Reason.RECOVERING, healthy(gate, 3500));
        assertEquals(PregenAdmission.Reason.READY, healthy(gate, 6500));
    }

    @Test
    void cancellationNeverCreatesAdmissionSlots() {
        assertFalse(PregenAdmission.canIssue(0, 0, 0));
        assertFalse(PregenAdmission.canIssue(1, 1, 1));
        assertFalse(PregenAdmission.canIssue(1, 1, 4));
        assertFalse(PregenAdmission.canIssue(4, 0, 4));
        assertTrue(PregenAdmission.canIssue(0, 0, 1));
        assertTrue(PregenAdmission.canIssue(1, 0, 2));
    }

    @Test
    void frameGateHonorsLowCapsButRejectsHitchesAndMissingFrames() {
        assertTrue(PregenAdmission.framesHealthy(60, 60, 16_666_667));
        assertTrue(PregenAdmission.framesHealthy(30, 30, 33_333_334));
        assertTrue(PregenAdmission.framesHealthy(120, 120, 8_333_334));
        assertFalse(PregenAdmission.framesHealthy(60, 120, 16_666_667));
        assertFalse(PregenAdmission.framesHealthy(40, 60, 16_666_667));
        assertFalse(PregenAdmission.framesHealthy(60, 60, 35_000_000));
        assertFalse(PregenAdmission.framesHealthy(60, 60, 0));
    }

    @Test
    void excludesPlayerViewAndGenerationDependencyHaloIncludingDiagonals() {
        assertFalse(PregenAdmission.outsidePlayerView(20, 20, 0, 0, 12, 8, 4));
        assertTrue(PregenAdmission.outsidePlayerView(21, 20, 0, 0, 12, 8, 4));
        assertFalse(PregenAdmission.outsidePlayerView(-20, -20, 0, 0, 12, 8, 4));
        assertTrue(PregenAdmission.outsidePlayerView(-21, 0, 0, 0, 12, 8, 4));
        assertFalse(PregenAdmission.outsidePlayerView(30, 0, 0, 0, 2, 8, 30));
        assertFalse(PregenAdmission.outsidePlayerView(23, 0, 0, 0, 12, 11, 4));
        assertTrue(PregenAdmission.outsidePlayerView(24, 0, 0, 0, 12, 11, 4));
    }

    @Test
    void foreignDemandUsesTrackedViewRatherThanUnrequestedSquareCorners() {
        var view = ChunkTrackingView.of(new ChunkPos(0, 0), 12);
        assertTrue(view.isInViewDistance(12, 0));
        assertFalse(view.isInViewDistance(12, 12));
        assertFalse(view.isInViewDistance(-12, -12));
        assertFalse(ChunkTrackingView.EMPTY.isInViewDistance(0, 0));
    }

    @Test
    void staleMeshSignalFailsClosedAndAnyPendingMeshWorkIsPressure() {
        assertEquals(MeshPressure.Signal.NO_SIGNAL, MeshPressure.current());
        assertEquals(MeshPressure.Signal.CONGESTED, MeshPressure.classify(1, 0, 8, true));
        assertEquals(MeshPressure.Signal.CONGESTED, MeshPressure.classify(0, 1, 8, true));
        assertEquals(MeshPressure.Signal.CONGESTED, MeshPressure.classify(0, 0, 0, true));
        assertEquals(MeshPressure.Signal.CONGESTED, MeshPressure.classify(0, 0, 8, false));
        assertEquals(MeshPressure.Signal.OK, MeshPressure.classify(0, 0, 8, true));
    }
}
