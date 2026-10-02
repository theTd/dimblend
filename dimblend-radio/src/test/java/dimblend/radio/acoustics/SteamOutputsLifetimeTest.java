package dimblend.radio.acoustics;

import java.lang.ref.Reference;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Locks the JNA ownership convention: embedded params structures share the owning
 * SimulationOutputs' native backing, so the owner must stay reachable while the params are
 * rendered. (Dropping it was reproduced to zero the wet field after GC: 0.274 → 0.0.)
 */
class SteamOutputsLifetimeTest {
    private static AcousticMesh.Data room() {
        float[] vertices = {-8,-8,-8, 8,-8,-8, 8,8,-8, -8,8,-8,
                -8,-8,8, 8,-8,8, 8,8,8, -8,8,8};
        int[] triangles = {0,2,1,0,3,2, 4,5,6,4,6,7, 0,1,5,0,5,4,
                3,7,6,3,6,2, 0,4,7,0,7,3, 1,2,6,1,6,5};
        int[] materials = new int[triangles.length / 3];
        java.util.Arrays.fill(materials, 4);
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    @Test
    void wetEnergySurvivesGcWithPinnedOutputs() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 3, true);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            // The session pins the whole SimulationOutputs object alongside the params (same pattern).
            var outputs = simulation.simulateGpu(room(), Vec3.ZERO, new Vec3(4, 0, 0), 64, 128);
            var impulse = outputs.reflections;
            double healthy = wet(renderer, impulse);
            System.out.println("wet energy before GC: " + healthy);
            assertTrue(healthy > 0.01, "sanity: room must produce wet energy, got " + healthy);
            System.gc();
            System.gc();
            double after = wet(renderer, impulse);
            System.out.println("wet energy after GC (pinned outputs): " + after);
            assertTrue(after > healthy * 0.5, "pinned outputs must keep the params alive: "
                    + healthy + " -> " + after);
            Reference.reachabilityFence(outputs);
        }
    }

    private static double wet(SteamRenderer renderer, SteamAudio.ReflectionParams impulse) {
        var muted = new SteamAudio.DirectParams();
        muted.distance = 0;
        double energy = 0;
        for (int block = 0; block < 30; block++) {
            float[] input = new float[SteamRenderer.FRAME];
            if (block == 0) input[0] = 1;
            float[][] output = renderer.render(input, muted, impulse, new Vec3(4, 0, 0),
                    new SteamAudio.Space(), false, 1f);
            for (float[] channel : output) for (float sample : channel) energy += sample * (double) sample;
        }
        return energy;
    }
}
