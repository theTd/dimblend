package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/** Prints the direct-sim outputs for the CPU callback path (the in-game direct engine's path). */
class SteamDirectCpuTransmissionTest {
    @Test
    void directThroughOneWallCpu() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1)) {
            var outputs = simulation.simulate((from, to) -> {
                // Wall plane x=2: report a hit with a stone-ish reflectivity (material index default).
                Vec3 hit = new Vec3(2, from.y, from.z);
                return new AcousticRay(AcousticRay.Kind.HIT, hit, new Vec3(-1, 0, 0), 0.9f);
            }, Vec3.ZERO, new Vec3(4, 0, 0), 1, 0);
            System.out.println("[direct-cpu] occlusion=" + outputs.direct.occlusion
                    + " distance=" + outputs.direct.distance
                    + " transmission=" + java.util.Arrays.toString(outputs.direct.transmission));
            assertEquals(0, outputs.direct.occlusion, 0.001);
            assertEquals(0.25, outputs.direct.distance, 0.001);
            assertTrue(outputs.direct.transmission[0] > outputs.direct.transmission[2]);
        }
    }
}
