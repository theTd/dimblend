package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/** Prints the direct-sim outputs for a source behind a single wall. */
class SteamDirectTransmissionTest {
    @Test
    void directThroughOneWall() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        // Wall plane x=2, source at (4,0,0), listener at origin.
        var wall = new AcousticMesh.Data(new float[] {2,-8,-8, 2,8,-8, 2,8,8, 2,-8,8},
                new int[] {0,1,2,0,2,3}, new int[] {4,4}, Vec3.ZERO);
        try (var simulation = new SteamSimulation(44100, 1, true)) {
            var outputs = simulation.simulateGpu(wall, Vec3.ZERO, new Vec3(4, 0, 0), 1, 0);
            assertEquals(0, outputs.direct.occlusion, 0.001);
            assertTrue(outputs.direct.transmission[0] > outputs.direct.transmission[2]);
            System.out.println("[direct] occlusion=" + outputs.direct.occlusion
                    + " distance=" + outputs.direct.distance
                    + " transmission=" + java.util.Arrays.toString(outputs.direct.transmission)
                    + " air=" + java.util.Arrays.toString(outputs.direct.air));
            var clear = simulation.simulateGpu(
                    new AcousticMesh.Data(new float[0], new int[0], new int[0], Vec3.ZERO),
                    Vec3.ZERO, new Vec3(4, 0, 0), 1, 0);
            System.out.println("[direct] open: occlusion=" + clear.direct.occlusion
                    + " distance=" + clear.direct.distance
                    + " transmission=" + java.util.Arrays.toString(clear.direct.transmission));
            assertEquals(1, clear.direct.occlusion, 0.001);
        }
    }
}
