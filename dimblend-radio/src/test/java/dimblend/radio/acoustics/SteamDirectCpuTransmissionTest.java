package dimblend.radio.acoustics;

import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct transmission on the CPU callback path (the in-game direct engine) through real voxel
 * walls: Steam Audio re-casts from just past every hit, so this exercises the tracer's handling
 * of rays that start inside a wall, and the per-material, per-thickness transmission.
 */
class SteamDirectCpuTransmissionTest {
    private static final Vec3 LISTENER = new Vec3(-3.5, 0.5, 0.5), SOURCE = new Vec3(6.5, 0.5, 0.5);
    private static final float STONE = 0.9f, WOOL = 0.1f;

    @Test
    void transmissionDependsOnMaterialThicknessAndWallCount() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1)) {
            float[] stone = transmission(simulation, STONE, 0);
            float[] thick = transmission(simulation, STONE, 0, 1, 2);
            float[] twoWalls = transmission(simulation, STONE, 0, 2);
            float[] wool = transmission(simulation, WOOL, 0);
            float[] reference = AcousticMaterials.transmission(4, 1);
            for (int band = 0; band < 3; band++) {
                assertEquals(reference[band], stone[band], reference[band] * 0.02, "one block of stone, band " + band);
                assertEquals(reference[band] / 3, thick[band], reference[band] * 0.02, "three blocks: mass law, band " + band);
                assertEquals(reference[band] * reference[band], twoWalls[band], reference[band] * 0.02,
                        "two walls multiply, band " + band);
            }
            assertArrayEquals(AcousticMaterials.transmission(0, 1), wool, 0.01f);
            assertTrue(wool[0] > stone[0], "wool passes more low end than stone");
        }
    }

    @Test
    void openPathIsNotOccluded() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1)) {
            var outputs = simulate(simulation, STONE);
            assertEquals(1, outputs.direct.occlusion, 0.001);
        }
    }

    private static float[] transmission(SteamSimulation simulation, float reflectivity, int... wallXs) {
        var outputs = simulate(simulation, reflectivity, wallXs);
        System.out.println("[direct-cpu] walls=" + Arrays.toString(wallXs) + " reflectivity=" + reflectivity
                + " occlusion=" + outputs.direct.occlusion + " transmission=" + Arrays.toString(outputs.direct.transmission));
        assertEquals(0, outputs.direct.occlusion, 0.001);
        return outputs.direct.transmission.clone();
    }

    /** Full-block walls spanning y,z in [-8, 8] at the given x positions. */
    private static SteamAudio.SimulationOutputs simulate(SteamSimulation simulation, float reflectivity, int... wallXs) {
        var cell = new AcousticVoxelTrace.Cell(Shapes.block(), reflectivity);
        AcousticVoxelTrace.Lookup lookup = pos -> Math.abs(pos.getY()) <= 8 && Math.abs(pos.getZ()) <= 8
                && Arrays.stream(wallXs).anyMatch(x -> x == pos.getX()) ? cell : null;
        return simulation.simulate((from, to) -> AcousticVoxelTrace.cast(from, to, lookup), LISTENER, SOURCE, 1, 0);
    }
}
