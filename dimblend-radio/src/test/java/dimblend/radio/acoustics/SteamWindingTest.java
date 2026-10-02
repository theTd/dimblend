package dimblend.radio.acoustics;

import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Does phonon's GPU reflection sim care about triangle winding? */
class SteamWindingTest {
    @Test
    void windingFlip() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        float[] vertices = {-8,-8,-8, 8,-8,-8, 8,8,-8, -8,8,-8,
                -8,-8,8, 8,-8,8, 8,8,8, -8,8,8};
        int[] triangles = {0,2,1,0,3,2, 4,5,6,4,6,7, 0,1,5,0,5,4,
                3,7,6,3,6,2, 0,4,7,0,7,3, 1,2,6,1,6,5};
        int[] flipped = new int[triangles.length];
        for (int i = 0; i < triangles.length; i += 3) {
            flipped[i] = triangles[i];
            flipped[i + 1] = triangles[i + 2];
            flipped[i + 2] = triangles[i + 1];
        }
        int[] materials = new int[triangles.length / 3];
        Arrays.fill(materials, 4);
        System.out.println("[winding] as-is: " + wet(new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO)));
        System.out.println("[winding] flipped: " + wet(new AcousticMesh.Data(vertices, flipped, materials, Vec3.ZERO)));
    }

    private static double wet(AcousticMesh.Data data) {
        try (var simulation = new SteamSimulation(44100, 2, true);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            var outputs = simulation.simulateGpu(data, Vec3.ZERO, new Vec3(4, 0, 0), 64, 128);
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            double energy = 0;
            for (int block = 0; block < 30; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 0) input[0] = 1;
                float[][] output = renderer.render(input, muted, outputs.reflections, new Vec3(4, 0, 0),
                        new SteamAudio.Space(), false, 1f);
                for (float[] channel : output) for (float sample : channel) energy += sample * (double) sample;
            }
            return energy;
        }
    }
}
