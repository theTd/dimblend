package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** In-game topology: a CPU direct engine and a GPU reflections engine alive in one JVM. */
class SteamTwoContextTest {
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
    void wetSurvivesWithDirectEngineAlive() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var direct = new SteamSimulation(44100, 1);
                var reflections = new SteamSimulation(44100, 2, true);
                var renderer = new SteamRenderer(reflections.context(), 44100)) {
            var directOut = direct.simulate((from, to) -> AcousticRay.miss(to), Vec3.ZERO, new Vec3(4, 0, 0), 1, 0);
            System.out.println("[two-ctx] direct occlusion=" + directOut.direct.occlusion);
            double wet1 = wet(renderer,
                    reflections.simulateGpu(room(), Vec3.ZERO, new Vec3(4, 0, 0), 64, 128).reflections);
            System.out.println("[two-ctx] wet with direct engine alive: " + wet1);
            direct.simulate((from, to) -> AcousticRay.miss(to), Vec3.ZERO, new Vec3(4, 0, 0), 1, 0);
            double wet2 = wet(renderer,
                    reflections.simulateGpu(room(), Vec3.ZERO, new Vec3(4, 0, 0), 64, 128).reflections);
            System.out.println("[two-ctx] wet after interleaved direct run: " + wet2);
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
