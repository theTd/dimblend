package dimblend.radio.acoustics;

import java.lang.ref.Reference;
import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reflections against terrain and a moving structure, uploaded together as one GPU mesh. */
class SteamSceneReflectionsTest {
    private static AcousticMesh.Data room() {
        float[] vertices = {-8,-8,-8,8,-8,-8,8,8,-8,-8,8,-8,-8,-8,8,8,-8,8,8,8,8,-8,8,8};
        int[] triangles = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        int[] materials = new int[12];
        Arrays.fill(materials, 4);
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    private static AcousticMesh.Data panel(float x) {
        return new AcousticMesh.Data(new float[] {x,-2,-2, x,2,-2, x,2,2, x,-2,2},
                new int[] {0,1,2,0,2,3}, new int[] {2,2}, Vec3.ZERO);
    }

    @Test void movingStructureInsideTerrainKeepsProducingFiniteReflections() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 2, true)) {
            var terrain = room();
            assertNotNull(simulation.simulateGpu(terrain, null, Vec3.ZERO, new Vec3(4, 0, 0), 64, 128).reflections.ir);
            for (int step = 0; step < 20; step++) {
                var outputs = simulation.simulateGpu(terrain, panel(-6 + step * 0.25f), Vec3.ZERO, new Vec3(4, 0, 0), 64, 128);
                assertNotNull(outputs.reflections.ir, "step " + step);
            }
        }
    }

    /** An inward-facing box of {@code half} radius, each face split into {@code n} x {@code n} quads. */
    private static AcousticMesh.Data tessellatedRoom(float half, int n, int material) {
        int quads = 6 * n * n;
        float[] vertices = new float[quads * 12];
        int[] triangles = new int[quads * 6];
        int[] materials = new int[quads * 2];
        Arrays.fill(materials, material);
        int q = 0;
        for (int axis = 0; axis < 3; axis++) for (int side = -1; side <= 1; side += 2) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                float u0 = -half + 2 * half * i / n, u1 = -half + 2 * half * (i + 1) / n;
                float v0 = -half + 2 * half * j / n, v1 = -half + 2 * half * (j + 1) / n;
                float[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                for (int c = 0; c < 4; c++) {
                    vertices[q * 12 + c * 3 + axis] = side * half;
                    vertices[q * 12 + c * 3 + u] = corners[c][0];
                    vertices[q * 12 + c * 3 + v] = corners[c][1];
                }
                int base = q * 4;
                int[] order = side > 0 ? new int[] {0, 1, 2, 0, 2, 3} : new int[] {0, 2, 1, 0, 3, 2};
                for (int k = 0; k < 6; k++) triangles[q * 6 + k] = base + order[k];
                q++;
            }
        }
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    /**
     * Game-sized meshes of very different sizes, in both roles, replaced as a moving train is. As two
     * GPU scene meshes, swapping the terrain for a smaller one under a structure faulted
     * {@code iplSimulatorRunReflections} with "Invalid memory access" at step 13.
     */
    @Test void largeMeshesOfDifferentSizesInEitherRole() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 2, true)) {
            var listener = Vec3.ZERO;
            var source = new Vec3(4, 0, 0);
            var terrain = tessellatedRoom(40, 64, 4);
            for (int step = 0; step < 24; step++) {
                var train = tessellatedRoom(6 + step * 0.05f, step % 2 == 0 ? 8 : 24, 1);
                if (step == 12) terrain = tessellatedRoom(40, 16, 3);
                var outputs = simulation.simulateGpu(terrain, train, listener, source, 64, 128);
                assertNotNull(outputs.reflections.ir, "step " + step);
            }
        }
    }

    /**
     * Structure surfaces reflect with their own materials. The GPU solver shades every hit with its
     * first scene mesh's buffers, so a structure uploaded as a second mesh rang like the terrain.
     */
    @Test void structureSurfacesKeepTheirOwnMaterials() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var terrain = tessellatedRoom(40, 16, 4);
        double stone = wetEnergy(terrain, tessellatedRoom(8, 2, 4));
        double foliage = wetEnergy(terrain, tessellatedRoom(8, 2, 1));
        assertTrue(stone > foliage * 2, "a stone room must ring longer than a leafy one: " + stone + " vs " + foliage);
    }

    private static double wetEnergy(AcousticMesh.Data terrain, AcousticMesh.Data structure) {
        var source = new Vec3(4, 0, 0);
        try (var simulation = new SteamSimulation(44100, 3, true);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            var outputs = simulation.simulateGpu(terrain, structure, Vec3.ZERO, source, 64, 128);
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            double energy = 0;
            for (int block = 0; block < 30; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 0) input[0] = 1;
                for (float[] channel : renderer.render(input, muted, outputs.reflections, source, new SteamAudio.Space(), false, 1f)) {
                    for (float sample : channel) energy += sample * (double) sample;
                }
            }
            Reference.reachabilityFence(outputs);
            return energy;
        }
    }

    /** Any mesh may be replaced while the other stays: the first one added is not always the first removed. */
    @Test void eitherMeshCanBeReplacedOrRemovedWhileTheOtherStays() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 2, true)) {
            var listener = Vec3.ZERO;
            var source = new Vec3(4, 0, 0);
            var structure = panel(-6);
            for (int step = 0; step < 12; step++) {
                // A fresh terrain array each time, as after a listener move or a section edit.
                var terrain = room();
                switch (step % 4) {
                    case 0 -> simulation.simulateGpu(terrain, structure, listener, source, 64, 128);
                    case 1 -> simulation.simulateGpu(room(), structure, listener, source, 64, 128);
                    case 2 -> simulation.simulateGpu(room(), null, listener, source, 64, 128);
                    default -> simulation.simulateGpu(room(), structure = panel(-5 + step * 0.1f), listener, source, 64, 128);
                }
                var outputs = simulation.simulateGpu(room(), structure, listener, source, 64, 128);
                assertNotNull(outputs.reflections.ir, "step " + step);
            }
        }
    }
}
