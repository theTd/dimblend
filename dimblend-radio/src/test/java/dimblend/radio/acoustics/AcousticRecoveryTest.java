package dimblend.radio.acoustics;

import dimblend.radio.acoustics.terrain.SectionMeshDecoder;
import dimblend.radio.acoustics.terrain.SectionQuads;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regressions reproduced during the acoustics stability review. */
class AcousticRecoveryTest {
    @Test void partiallyDegenerateQuadMustNotReachGpu() {
        float[] corners = {0,0,0, 1,0,0, 1,0,0, 0,1,0};
        ByteBuffer packed = ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            int hi = 0, lo = 0;
            for (int axis = 0; axis < 3; axis++) {
                int q = (int) ((8 + corners[i * 3 + axis]) / 32 * (1 << 20));
                hi |= ((q >>> 10) & 1023) << (axis * 10);
                lo |= (q & 1023) << (axis * 10);
            }
            packed.putInt(hi).putInt(lo).putInt(0).putInt(0).putInt(0);
        }
        float[] vertices = new float[12];
        byte[] materials = new byte[1], owners = new byte[3];
        int count = SectionMeshDecoder.decode(0, 0, 0, packed.flip(),
                (x,y,z,nx,ny,nz,owner) -> 4, vertices, materials, owners, 0);
        AcousticMesh mesh = new AcousticMesh(Vec3.ZERO);
        if (count > 0) mesh.appendSections(List.of(new SectionQuads(0,0,0,vertices,materials,owners,1)), null);
        var data = mesh.data();
        for (int i = 0; i < data.triangles().length; i += 3) {
            Vec3 a = vertex(data, i), b = vertex(data, i+1), c = vertex(data, i+2);
            assertTrue(b.subtract(a).cross(c.subtract(a)).lengthSqr() > 1e-12,
                    "Zero-area triangle passed decoder and GPU mesh assembly");
        }
    }

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
    private static Vec3 vertex(AcousticMesh.Data data, int index) {
        int v = data.triangles()[index] * 3;
        return new Vec3(data.vertices()[v], data.vertices()[v+1], data.vertices()[v+2]);
    }
}
