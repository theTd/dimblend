package dimblend.radio.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioStereoPannerTest {
    private static final Vec3 AHEAD = new Vec3(0, 0, -1), UP = new Vec3(0, 1, 0);

    @Test void equalPowerLawFollowsTheSourceSideAndTheDistanceCurve() {
        float[] right = RadioStereoPanner.gains(new Vec3(4, 0, 0), AHEAD, UP);
        float[] left = RadioStereoPanner.gains(new Vec3(-4, 0, 0), AHEAD, UP);
        float[] front = RadioStereoPanner.gains(new Vec3(0, 0, -4), AHEAD, UP);
        float gain = RadioSimulationSession.distanceGain(4);
        assertEquals(0, right[0], 1e-6);
        assertEquals(gain, right[1], 1e-6);
        assertEquals(gain, left[0], 1e-6);
        assertEquals(0, left[1], 1e-6);
        assertEquals(gain * Math.sqrt(0.5), front[0], 1e-6, "centre keeps the old mono fallback level");
        assertEquals(front[0], front[1], 1e-6);
        float[] diagonal = RadioStereoPanner.gains(new Vec3(3, 0, -3), AHEAD, UP);
        float d = RadioSimulationSession.distanceGain(Math.sqrt(18));
        assertEquals(d * d, diagonal[0] * diagonal[0] + diagonal[1] * diagonal[1], 1e-5, "constant power");
        assertArrayEquals(new float[] {0, 0}, RadioStereoPanner.gains(new Vec3(200, 0, 0), AHEAD, UP), 1e-6f);
    }

    @Test void gainsRampAcrossABlockInsteadOfStepping() {
        var panner = new RadioStereoPanner();
        float[] ones = new float[512];
        java.util.Arrays.fill(ones, 1);
        float[][] first = panner.process(ones, new Vec3(4, 0, 0), AHEAD, UP);
        assertEquals(0, first[0][0], 1e-6, "the first block starts at its target");
        float[][] swapped = panner.process(ones, new Vec3(-4, 0, 0), AHEAD, UP);
        assertTrue(swapped[0][0] < 0.01f && swapped[1][0] > 0.9f, "starts where the previous block ended");
        assertTrue(swapped[0][511] > 0.9f && swapped[1][511] < 0.01f, "ends at the new target");
        panner.reset();
        float[][] snapped = panner.process(ones, new Vec3(4, 0, 0), AHEAD, UP);
        assertEquals(0, snapped[0][0], 1e-6, "after a reset there is nothing to ramp from");
    }
}
