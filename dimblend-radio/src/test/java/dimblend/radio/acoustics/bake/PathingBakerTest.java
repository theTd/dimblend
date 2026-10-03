package dimblend.radio.acoustics.bake;

import dimblend.radio.acoustics.AcousticMaterials;
import dimblend.radio.acoustics.AcousticMesh;
import dimblend.radio.acoustics.AcousticPathing;
import dimblend.radio.acoustics.AcousticRay;
import dimblend.radio.acoustics.PathingField;
import dimblend.radio.acoustics.SteamAudio;
import dimblend.radio.acoustics.SteamRenderer;
import dimblend.radio.acoustics.SteamSimulation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bake, attach, look up and render a radio's pathing in a hall split by a partition with an 8-block
 * gap at its +z end, placed away from the world origin so every frame conversion is exercised.
 */
class PathingBakerTest {
    private static final BlockPos BASE = new BlockPos(100, 70, -200);
    private static final BlockPos RADIO = BASE.offset(-16, 0, 4);
    private static final Vec3 SOURCE = Vec3.atCenterOf(RADIO);
    /** Behind the partition, far from the gap. */
    private static final Vec3 HIDDEN = Vec3.atLowerCornerOf(BASE).add(8.5, 1.6, -5.5);
    /** In the radio's half of the hall. */
    private static final Vec3 IN_VIEW = Vec3.atLowerCornerOf(BASE).add(-8.5, 1.6, 0.5);
    private static final DoubleUnaryOperator LINEAR = d -> Math.max(0, 1 - d / 96);
    private static final double W_UNIT = 0.28209479177387814;

    /** Boxes in world coordinates: min xyz, max xyz, and 1 for a room (faces inward) or 0 for a solid. */
    private static List<double[]> hall() {
        Vec3 base = Vec3.atLowerCornerOf(BASE);
        List<double[]> boxes = new ArrayList<>();
        boxes.add(new double[] {base.x - 24, base.y, base.z - 16, base.x + 24, base.y + 12, base.z + 16, 1});
        boxes.add(new double[] {base.x, base.y, base.z - 16, base.x + 1, base.y + 12, base.z + 8, 0});
        return boxes;
    }

    private static byte cell(int x, int y, int z) {
        int lx = x - BASE.getX(), ly = y - BASE.getY(), lz = z - BASE.getZ();
        if (x == RADIO.getX() && y == RADIO.getY() && z == RADIO.getZ()) return PathingProbePlacement.SOLID;
        if (lx < -24 || lx > 23 || ly < 0 || ly > 11 || lz < -16 || lz > 15) return PathingProbePlacement.SOLID;
        if (lx == 0 && lz <= 7) return PathingProbePlacement.SOLID;
        return PathingProbePlacement.OPEN;
    }

    private static AcousticMesh.Data mesh(List<double[]> boxes, Vec3 origin) {
        List<float[]> quads = new ArrayList<>();
        for (double[] b : boxes) {
            double[][] c = {{b[0], b[1], b[2]}, {b[3], b[1], b[2]}, {b[3], b[4], b[2]}, {b[0], b[4], b[2]},
                    {b[0], b[1], b[5]}, {b[3], b[1], b[5]}, {b[3], b[4], b[5]}, {b[0], b[4], b[5]}};
            int[][] faces = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 7, 6, 2}, {0, 4, 7, 3}, {1, 2, 6, 5}};
            boolean inward = b[6] > 0;
            for (int[] f : faces) {
                float[] q = new float[12];
                for (int k = 0; k < 4; k++) {
                    double[] corner = c[f[inward ? 3 - k : k]];
                    q[k * 3] = (float) (corner[0] - origin.x);
                    q[k * 3 + 1] = (float) (corner[1] - origin.y);
                    q[k * 3 + 2] = (float) (corner[2] - origin.z);
                }
                quads.add(q);
            }
        }
        float[] vertices = new float[quads.size() * 12];
        int[] triangles = new int[quads.size() * 6];
        int[] materials = new int[quads.size() * 2];
        java.util.Arrays.fill(materials, AcousticMaterials.STONE);
        for (int q = 0; q < quads.size(); q++) {
            System.arraycopy(quads.get(q), 0, vertices, q * 12, 12);
            int v = q * 4;
            System.arraycopy(new int[] {v, v + 1, v + 2, v, v + 2, v + 3}, 0, triangles, q * 6, 6);
        }
        return new AcousticMesh.Data(vertices, triangles, materials, origin);
    }

    /** The same boxes for the direct worker's voxel-style ray callbacks, in world coordinates. */
    private static BiFunction<Vec3, Vec3, AcousticRay> tracer(List<double[]> boxes) {
        return (from, to) -> {
            Vec3 d = to.subtract(from);
            double best = Double.POSITIVE_INFINITY;
            Vec3 normal = null;
            for (double[] b : boxes) {
                boolean inward = b[6] > 0;
                for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 2; side++) {
                    double plane = b[axis + side * 3];
                    double o = axis == 0 ? from.x : axis == 1 ? from.y : from.z, dd = axis == 0 ? d.x : axis == 1 ? d.y : d.z;
                    if (Math.abs(dd) < 1e-12) continue;
                    double t = (plane - o) / dd;
                    if (t <= 1e-6 || t > 1 || t >= best) continue;
                    Vec3 p = from.add(d.scale(t));
                    double[] pc = {p.x, p.y, p.z};
                    boolean inside = true;
                    for (int a = 0; a < 3; a++) if (a != axis && (pc[a] < b[a] - 1e-6 || pc[a] > b[a + 3] + 1e-6)) inside = false;
                    if (!inside) continue;
                    best = t;
                    double sign = (side == 0 ? -1 : 1) * (inward ? -1 : 1);
                    normal = new Vec3(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
                }
            }
            if (normal == null) return AcousticRay.miss(to);
            return new AcousticRay(AcousticRay.Kind.HIT, from.add(d.scale(best)), normal, AcousticMaterials.STONE);
        };
    }

    private static byte[] bake() {
        var probes = PathingProbePlacement.place(PathingBakerTest::cell, RADIO, 40, 3000);
        assertTrue(probes.complete());
        assertTrue(probes.count() > 50, "probes on both sides of the partition: " + probes.count());
        try (var baker = new PathingBaker()) {
            double[] last = {0};
            byte[] bytes = baker.bake(mesh(hall(), Vec3.atLowerCornerOf(RADIO)), probes, 96, 2, fraction -> last[0] = fraction);
            assertTrue(bytes.length > 1000, "batch bytes " + bytes.length);
            assertEquals(1, last[0], 1e-6, "progress reaches the end");
            return bytes;
        }
    }

    @Test
    void aBakedPathBendsThroughTheGapToAHiddenListenerOnly() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        byte[] bytes = bake();
        var tracer = tracer(hall());
        Vec3 frame = Vec3.atLowerCornerOf(RADIO);
        try (var simulation = new SteamSimulation(44100, SteamSimulation.DIRECT | SteamSimulation.PATHING)) {
            assertFalse(simulation.hasPathing());
            simulation.attachPathing(bytes);
            assertTrue(simulation.hasPathing());

            var hiddenDirect = simulation.simulate(tracer, HIDDEN, SOURCE, 1, 0).direct;
            assertTrue(hiddenDirect.occlusion < 0.1, "the partition hides the radio: " + hiddenDirect.occlusion);
            var raw = simulation.runPathing(tracer, frame, HIDDEN, SOURCE, false);
            double length = W_UNIT / raw.sh()[0];
            double straight = HIDDEN.distanceTo(SOURCE);
            assertTrue(length > straight + 2 && length < straight + 20,
                    "the path goes round by the gap: " + length + " against " + straight + " straight");
            PathingField field = AcousticPathing.shape(raw.eq(), raw.sh(), hiddenDirect.occlusion, 1, LINEAR);
            assertNotNull(field);
            assertTrue(field.eq()[0] > 0.05, "low frequencies bend round the edge: " + field.eq()[0]);
            assertTrue(field.eq()[0] >= field.eq()[2], "highs bend less");
            assertEquals(W_UNIT * LINEAR.applyAsDouble(length), field.sh()[0], 1e-4);
            // Steam's first-order terms: sh[1] grows with arrival from -x, sh[3] with arrival from +z.
            // The straight line comes mostly along x; the path through the gap mostly along +z.
            assertTrue(-field.sh()[3] > Math.abs(field.sh()[1]),
                    "it arrives from the gap, not along the straight line: " + java.util.Arrays.toString(field.sh()));

            var validated = simulation.runPathing(tracer, frame, HIDDEN, SOURCE, true);
            assertEquals(raw.sh()[0], validated.sh()[0], raw.sh()[0] * 0.05, "nothing changed: validation keeps the route");

            var inViewDirect = simulation.simulate(tracer, IN_VIEW, SOURCE, 1, 0).direct;
            assertTrue(inViewDirect.occlusion > 0.9);
            var visible = simulation.runPathing(tracer, frame, IN_VIEW, SOURCE, false);
            assertNull(AcousticPathing.shape(visible.eq(), visible.sh(), inViewDirect.occlusion, 1, LINEAR),
                    "in view the direct path carries it all");

            simulation.attachPathing(null);
            assertFalse(simulation.hasPathing());
            assertThrows(IllegalStateException.class, () -> simulation.runPathing(tracer, frame, HIDDEN, SOURCE, false));
        }
    }

    @Test
    void aBakeSavedToDiskFindsTheSamePathInAnotherSimulator(@TempDir Path root) throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        byte[] bytes = bake();
        var files = new AcousticBakeFiles(root);
        Path file = files.pathingFile("w", "d", RADIO);
        files.write(file, new PathingBake(RADIO, new long[] {5}, new long[] {6}, 100, 4, bytes), 1);
        var read = files.read(file, RADIO, 1);
        assertNotNull(read);
        var tracer = tracer(hall());
        float[] first = null;
        for (byte[] batch : new byte[][] {bytes, read.batch()}) {
            try (var simulation = new SteamSimulation(44100, SteamSimulation.DIRECT | SteamSimulation.PATHING)) {
                simulation.attachPathing(batch);
                float[] sh = simulation.runPathing(tracer, read.origin(), HIDDEN, SOURCE, false).sh();
                assertTrue(sh[0] > 0);
                if (first == null) first = sh;
                else assertArrayEquals(first, sh, 1e-6f, "the same path from the file");
            }
        }
    }

    @Test
    void theRendererAddsThePathAndFadesItOutWhenItGoes() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, SteamSimulation.DIRECT | SteamSimulation.PATHING);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            var silentDirect = new SteamAudio.DirectParams();
            silentDirect.flags = 8;
            silentDirect.occlusion = 0;
            Vec3 relative = new Vec3(0, 0, -20);
            var field = new PathingField(new float[] {0.8f, 0.6f, 0.4f}, new float[] {0.2f, 0, 0, 0.15f});
            Random random = new Random(7);
            double[] energy = new double[40];
            for (int block = 0; block < energy.length; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                for (int i = 0; i < input.length; i++) input[i] = (float) random.nextGaussian() * 0.1f;
                PathingField now = block < 30 ? field : null;
                var prepared = renderer.prepare(input, silentDirect, null, relative, false, now);
                float[][] output = renderer.spatialize(prepared, relative, new SteamAudio.Space(), 0);
                for (float[] channel : output) for (float sample : channel) energy[block] += sample * (double) sample;
            }
            assertTrue(energy[25] > 1e-3, "the path is heard with no direct sound and no reflections: " + energy[25]);
            assertTrue(energy[30] < energy[29], "the block after it goes fades out");
            assertTrue(energy[34] < energy[25] * 1e-4, "then silence: " + energy[34]);
        }
    }
}
