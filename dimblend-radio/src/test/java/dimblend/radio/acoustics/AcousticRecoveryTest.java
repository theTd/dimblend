package dimblend.radio.acoustics;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regressions reproduced during the acoustics stability review. */
class AcousticRecoveryTest {
    @Test void nonFiniteRecoveryMustRestoreWetWithoutPlayerMovement() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        float[] vertices = {-8,-8,-8,8,-8,-8,8,8,-8,-8,8,-8,-8,-8,8,8,-8,8,8,8,8,-8,8,8};
        int[] triangles = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        int[] materials = new int[12];
        Arrays.fill(materials, 4);
        try (var simulation = new SteamSimulation(44100, 2, true)) {
            var out = simulation.simulateGpu(new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO),
                    Vec3.ZERO, new Vec3(4,0,0), 64,128);
            Object owner = new Object(), terrain = new Object(), snapshot = new Object();
            AcousticUpdateGate.registerTerrain(terrain, new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(),
                    it.unimi.dsi.fastutil.longs.LongSet.of(1L));
            AcousticUpdateGate.registerSnapshot(snapshot, terrain, List.of(), List.of(), List.of());
            assertTrue(AcousticUpdateGate.shouldSimulate(owner, snapshot, Vec3.ZERO, Vec3.ZERO, true));
            assertFalse(AcousticUpdateGate.shouldSimulate(owner, snapshot, Vec3.ZERO, Vec3.ZERO, true));
            try (var renderer = new SteamRenderer(simulation.context(), 44100,
                    () -> AcousticUpdateGate.invalidateReflections(owner))) {
                var muted = new SteamAudio.DirectParams();
                muted.distance = 0;
                double before = wet(renderer, muted, out);
                assertTrue(before > 1e-6);
                renderer.resetReflections();
                wet(renderer, muted, out); // drain the binaural decoder's residual tail
                double after = wet(renderer, muted, out);
                assertEquals(0, after, 1e-10, "Old output must remain withdrawn during recovery");
                assertTrue(AcousticUpdateGate.shouldSimulate(owner, snapshot, Vec3.ZERO, Vec3.ZERO, true),
                        "Reset must bypass the unchanged-scene gate");
                var refreshed = simulation.simulateGpu(new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO),
                        Vec3.ZERO, new Vec3(4,0,0), 64,128);
                renderer.reflectionsReady();
                double recovered = wet(renderer, muted, refreshed);
                assertTrue(Double.isFinite(recovered) && recovered > before * 0.1,
                        "Fresh output must restore wet sound without moving the player");
            }
        }
    }

    private static double wet(SteamRenderer renderer, SteamAudio.DirectParams muted, SteamAudio.SimulationOutputs out) {
        double sum = 0;
        for (int block=0; block<30; block++) {
            float[] input = new float[SteamRenderer.FRAME];
            input[0] = 0.5f;
            for (float[] channel : renderer.render(input, muted, out.reflections, new Vec3(4,0,0), new SteamAudio.Space(), false, 1))
                for (float sample : channel) sum += sample * (double) sample;
        }
        return sum;
    }
}
