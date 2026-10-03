package dimblend.radio.acoustics;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.PointerByReference;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scratch cost breakdown of one reflection run on real terrain voxels (build/cells-*.bin): Java
 * meshing, scene upload by phase, the steady run under different settings, Radeon Rays against
 * Embree, and moving an Embree instanced structure.
 */
class ReflectionCostScratchTest {
    private record Cells(String name, byte[] cells, int[] size) { }

    interface Api extends SteamBaking.Api {
        int iplInstancedMeshCreate(Pointer scene, InstancedMeshSettings settings, PointerByReference mesh);
        void iplInstancedMeshRelease(PointerByReference mesh);
        void iplInstancedMeshAdd(Pointer mesh, Pointer scene);
        void iplInstancedMeshRemove(Pointer mesh, Pointer scene);
        void iplInstancedMeshUpdateTransform(Pointer mesh, Pointer scene, Matrix.ByValue transform);
    }

    @FieldOrder({"elements"})
    public static class Matrix extends Structure {
        public float[] elements = new float[16];
        public static class ByValue extends Matrix implements Structure.ByValue { }
        static <T extends Matrix> T translation(T m, double x, double y, double z) {
            Arrays.fill(m.elements, 0);
            m.elements[0] = m.elements[5] = m.elements[10] = m.elements[15] = 1;
            m.elements[3] = (float) x;
            m.elements[7] = (float) y;
            m.elements[11] = (float) z;
            return m;
        }
    }

    @FieldOrder({"subScene", "transform"})
    public static class InstancedMeshSettings extends Structure {
        public Pointer subScene;
        public Matrix transform = new Matrix();
    }

    private static Api api;

    static synchronized Api api() {
        if (api == null) {
            SteamBaking.api();
            api = Native.load(SteamAudio.library().toString(), Api.class);
        }
        return api;
    }

    @Test void breakdown() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        AcousticMesh.Data car = structure();
        for (String name : new String[]{"overworld", "rotating"}) {
            Path file = Path.of("cells-" + name + ".bin"); // the test task runs in build/
            assumeTrue(Files.exists(file), "no exported cells");
            Cells cells = load(name, file);
            var mesh = new AcousticMesh(Vec3.ZERO);
            mesh.appendCells(cells.cells.clone(), new int[]{0, 0, 0}, cells.size);
            AcousticMesh.Data data = mesh.data();
            Vec3 listener = above(cells, cells.size[0] / 2, cells.size[2] / 2);
            Vec3 source = above(cells, cells.size[0] / 2 + 6, cells.size[2] / 2);
            System.out.printf("[cost] %s tris=%d structure_tris=%d%n", name, data.triangleCount(), car.triangleCount());
            for (int[] backend : new int[][]{{2, 1}, {1, 1}, {1, 4}}) {
                for (float[] config : new float[][]{{6, 128, 1024}, {2, 64, 512}}) {
                    try (var engine = new Engine(backend[0], backend[1], config[0])) {
                        long[][] phases = new long[3][3];
                        for (int i = 0; i < 3; i++) engine.upload(data, phases, i);
                        long[] runs = new long[8];
                        for (int i = 0; i < runs.length; i++) {
                            long start = System.nanoTime();
                            engine.run(listener, source, (int) config[2], (int) config[1], config[0]);
                            runs[i] = System.nanoTime() - start;
                        }
                        String moved = "";
                        if (backend[0] == 1) {
                            engine.addInstance(car, listener);
                            long[] updates = new long[6], movedRuns = new long[6];
                            for (int i = 0; i < updates.length; i++) {
                                long start = System.nanoTime();
                                engine.moveInstance(listener.add(i * 0.5, 0, 0));
                                updates[i] = System.nanoTime() - start;
                                start = System.nanoTime();
                                engine.run(listener, source, (int) config[2], (int) config[1], config[0]);
                                movedRuns[i] = System.nanoTime() - start;
                            }
                            moved = String.format(" instance_move_ms=%.2f run_with_instance_ms=%.1f", median(updates), median(movedRuns));
                        }
                        System.out.printf("[cost] %s %s threads=%d duration=%.0fs bounces=%d rays=%d upload_ms=%.1f (create %.1f, scene %.1f, simulator %.1f) run_ms=%.1f%s%n",
                                name, backend[0] == 2 ? "radeon" : "embree", backend[1], config[0], (int) config[1], (int) config[2],
                                median(phases[0]) + median(phases[1]) + median(phases[2]), median(phases[0]), median(phases[1]),
                                median(phases[2]), median(Arrays.copyOfRange(runs, 2, runs.length)), moved);
                    }
                }
            }
        }
    }

    /** Same scene and positions on both backends: wet energy per time window of a rendered impulse. */
    @Test void quality() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        double[] edges = {0, 0.05, 0.2, 0.5, 1, 2, 4, 6};
        for (String name : new String[]{"overworld", "rotating"}) {
            Path file = Path.of("cells-" + name + ".bin");
            assumeTrue(Files.exists(file), "no exported cells");
            Cells cells = load(name, file);
            var mesh = new AcousticMesh(Vec3.ZERO);
            mesh.appendCells(cells.cells.clone(), new int[]{0, 0, 0}, cells.size);
            AcousticMesh.Data data = mesh.data();
            // A listener inside terrain cover as well as one in the open.
            Vec3[][] spots = {{above(cells, 64, 64), above(cells, 70, 64)}, {cave(cells), cave(cells).add(3, 0, 0)}};
            for (Vec3[] spot : spots) {
                if (spot[0] == null) continue;
                for (float[] backend : new float[][]{{2, 1, 0.05f}, {1, 4, 0.05f}, {1, 4, 0.3f}, {1, 4, 0.6f}, {1, 4, 1f}}) {
                    try (var engine = new Engine((int) backend[0], (int) backend[1], 6)) {
                        engine.scattering = backend[2];
                        engine.upload(data, new long[3][1], 0);
                        StringBuilder line = new StringBuilder();
                        for (int trial = 0; trial < 2; trial++) {
                            var outputs = engine.run(spot[0], spot[1], 1024, 128, 6);
                            double[] windows = envelope(engine, outputs, spot[1].subtract(spot[0]), edges);
                            line.append(" trial").append(trial).append('=');
                            for (double w : windows) line.append(String.format("%.2e ", w));
                        }
                        System.out.printf("[quality] %s listener=%s %s scattering=%.2f windows(s)=%s%s%n", name, spot[0],
                                backend[0] == 2 ? "radeon" : "embree", backend[2], Arrays.toString(edges), line);
                    }
                }
            }
        }
    }

    /**
     * Rays start at the listener, so faces of air pockets it cannot reach and the outward faces of
     * the box boundary are never hit. Triangles, fill time, GPU upload and wet energy with and without them.
     */
    @Test void culling() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        double[] edges = {0, 0.05, 0.2, 0.5, 1, 2};
        for (String name : new String[]{"overworld", "rotating"}) {
            Path file = Path.of("cells-" + name + ".bin");
            assumeTrue(Files.exists(file), "no exported cells");
            Cells all = load(name, file);
            Vec3[] spots = {above(all, all.size[0] / 2, all.size[2] / 2), cave(all)};
            for (Vec3 spot : spots) {
                if (spot == null) continue;
                // The production box: listener and a source six blocks away, inflated by MARGIN + PADDING.
                int[] lo = new int[3], size = new int[3];
                double[] p = {spot.x, spot.y, spot.z};
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.max(0, (int) Math.floor(p[a] - 48));
                    size[a] = Math.min(all.size[a], (int) Math.ceil(p[a] + (a == 0 ? 54 : 48))) - lo[a];
                }
                byte[] box = new byte[size[0] * size[1] * size[2]];
                for (int z = 0; z < size[2]; z++) for (int y = 0; y < size[1]; y++) {
                    System.arraycopy(all.cells, ((z + lo[2]) * all.size[1] + y + lo[1]) * all.size[0] + lo[0], box, (z * size[1] + y) * size[0], size[0]);
                }
                Vec3 listener = spot.subtract(lo[0], lo[1], lo[2]);
                Vec3 source = listener.add(6, 0, 0);
                AcousticMesh.Data full = mesh(box.clone(), size);
                AcousticMesh.Data inner = dropBoundary(full, size);
                long[] fills = new long[5];
                byte[] filled = null;
                for (int i = 0; i < fills.length; i++) {
                    filled = box.clone();
                    long start = System.nanoTime();
                    fillUnreachable(filled, size, (int) listener.x, (int) listener.y, (int) listener.z);
                    fills[i] = System.nanoTime() - start;
                }
                AcousticMesh.Data culled = dropBoundary(mesh(filled, size), size);
                System.out.printf("[cull] %s listener=%s box=%s tris full=%d no_boundary=%d culled=%d (%.0f%%) fill_ms=%.1f%n",
                        name, spot, Arrays.toString(size), full.triangleCount(), inner.triangleCount(), culled.triangleCount(),
                        100.0 * culled.triangleCount() / full.triangleCount(), median(fills));
                try (var engine = new Engine(2, 1, 6)) {
                    for (var entry : new Object[][]{{"full", full}, {"culled", culled}}) {
                        AcousticMesh.Data data = (AcousticMesh.Data) entry[1];
                        long[][] phases = new long[3][3];
                        for (int i = 0; i < 3; i++) engine.upload(data, phases, i);
                        StringBuilder line = new StringBuilder();
                        long[] runs = new long[4];
                        for (int trial = 0; trial < runs.length; trial++) {
                            long start = System.nanoTime();
                            var outputs = engine.run(listener, source, 1024, 128, 6);
                            runs[trial] = System.nanoTime() - start;
                            if (trial >= 2) {
                                line.append(" trial").append(trial - 2).append('=');
                                for (double w : envelope(engine, outputs, source.subtract(listener), edges)) line.append(String.format("%.2e ", w));
                            }
                        }
                        System.out.printf("[cull]   radeon %s upload_ms=%.1f run_ms=%.1f windows%s%s%n", entry[0],
                                median(phases[0]) + median(phases[1]) + median(phases[2]), median(runs), Arrays.toString(edges), line);
                    }
                }
            }
        }
    }

    /** One run for k sources against k single-source runs, on the same uploaded scene. */
    @Test void sources() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        Path file = Path.of("cells-overworld.bin");
        assumeTrue(Files.exists(file), "no exported cells");
        Cells cells = load("overworld", file);
        AcousticMesh.Data data = mesh(cells.cells.clone(), cells.size);
        Vec3 listener = above(cells, 44, 70);
        try (var engine = new Engine(2, 1, 6, 4)) {
            engine.upload(data, new long[3][1], 0);
            Api api = engine.api;
            PointerByReference[] extra = new PointerByReference[3];
            for (int i = 0; i < extra.length; i++) {
                extra[i] = new PointerByReference();
                var settings = new SteamAudio.SourceSettings();
                settings.flags = SteamSimulation.REFLECTIONS;
                SteamAudio.check(api.iplSourceCreate(engine.simulator.getValue(), settings, extra[i]), "source");
                api.iplSourceAdd(extra[i].getValue(), engine.simulator.getValue());
            }
            api.iplSimulatorCommit(engine.simulator.getValue());
            Pointer[] all = {engine.source.getValue(), extra[0].getValue(), extra[1].getValue(), extra[2].getValue()};
            for (int k = 1; k <= 4; k++) {
                long[] runs = new long[8];
                for (int r = 0; r < runs.length; r++) {
                    for (int s = 0; s < 4; s++) {
                        var inputs = new SteamAudio.SimulationInputs();
                        inputs.flags = s < k ? SteamSimulation.REFLECTIONS : 0;
                        inputs.source.origin = new SteamAudio.Vector(listener.x + 3 + s * 4, listener.y, listener.z + s);
                        api.iplSourceSetInputs(all[s], SteamSimulation.REFLECTIONS, inputs);
                    }
                    var shared = new SteamAudio.SharedInputs();
                    shared.rays = 1024;
                    shared.bounces = 128;
                    shared.duration = 6;
                    shared.order = 1;
                    shared.listener.origin = new SteamAudio.Vector(listener.x, listener.y, listener.z);
                    api.iplSimulatorSetSharedInputs(engine.simulator.getValue(), SteamSimulation.REFLECTIONS, shared);
                    long start = System.nanoTime();
                    api.iplSimulatorRunReflections(engine.simulator.getValue());
                    runs[r] = System.nanoTime() - start;
                }
                System.out.printf("[sources] k=%d one_run_ms=%.1f%n", k, median(Arrays.copyOfRange(runs, 2, runs.length)));
            }
            for (var source : extra) {
                api.iplSourceRemove(source.getValue(), engine.simulator.getValue());
                api.iplSourceRelease(source);
            }
            api.iplSimulatorCommit(engine.simulator.getValue());
        }
    }

    private static AcousticMesh.Data mesh(byte[] cells, int[] size) {
        var mesh = new AcousticMesh(Vec3.ZERO);
        mesh.appendCells(cells, new int[]{0, 0, 0}, size);
        return mesh.data();
    }

    /** Air not 6-connected to the seed cell becomes solid. */
    private static void fillUnreachable(byte[] cells, int[] size, int sx, int sy, int sz) {
        int count = cells.length;
        var seen = new java.util.BitSet(count);
        int[] queue = new int[count];
        int head = 0, tail = 0;
        int seed = (sz * size[1] + sy) * size[0] + sx;
        if (cells[seed] != 0) return;
        seen.set(seed);
        queue[tail++] = seed;
        int strideY = size[0], strideZ = size[0] * size[1];
        while (head < tail) {
            int i = queue[head++];
            int x = i % size[0], y = (i / strideY) % size[1], z = i / strideZ;
            if (x > 0) tail = visit(cells, seen, queue, tail, i - 1);
            if (x < size[0] - 1) tail = visit(cells, seen, queue, tail, i + 1);
            if (y > 0) tail = visit(cells, seen, queue, tail, i - strideY);
            if (y < size[1] - 1) tail = visit(cells, seen, queue, tail, i + strideY);
            if (z > 0) tail = visit(cells, seen, queue, tail, i - strideZ);
            if (z < size[2] - 1) tail = visit(cells, seen, queue, tail, i + strideZ);
        }
        for (int i = 0; i < count; i++) if (cells[i] == 0 && !seen.get(i)) cells[i] = 1;
    }

    private static int visit(byte[] cells, java.util.BitSet seen, int[] queue, int tail, int i) {
        if (cells[i] != 0 || seen.get(i)) return tail;
        seen.set(i);
        queue[tail] = i;
        return tail + 1;
    }

    /** Without quads lying on the box faces: those face outward, away from every ray. */
    private static AcousticMesh.Data dropBoundary(AcousticMesh.Data data, int[] size) {
        float[] v = data.vertices();
        int quads = data.triangleCount() / 2;
        float[] vertices = new float[v.length];
        int[] triangles = new int[data.triangles().length], materials = new int[data.materials().length];
        int kept = 0;
        for (int q = 0; q < quads; q++) {
            boolean boundary = false;
            for (int a = 0; a < 3 && !boundary; a++) {
                float c = v[12 * q + a];
                boolean same = true;
                for (int k = 1; k < 4; k++) same &= v[12 * q + 3 * k + a] == c;
                boundary = same && (c == 0 || c == size[a]);
            }
            if (boundary) continue;
            System.arraycopy(v, 12 * q, vertices, 12 * kept, 12);
            for (int t = 0; t < 6; t++) triangles[6 * kept + t] = data.triangles()[6 * q + t] - 4 * q + 4 * kept;
            materials[2 * kept] = data.materials()[2 * q];
            materials[2 * kept + 1] = data.materials()[2 * q + 1];
            kept++;
        }
        return new AcousticMesh.Data(Arrays.copyOf(vertices, 12 * kept), Arrays.copyOf(triangles, 6 * kept),
                Arrays.copyOf(materials, 2 * kept), data.origin());
    }

    /** First air cell under a solid ceiling near the middle of the box, two cells above the floor. */
    private static Vec3 cave(Cells cells) {
        int[] s = cells.size;
        for (int dx = 0; dx < 40; dx++) for (int dz = 0; dz < 40; dz++) {
            int x = s[0] / 2 + dx - 20, z = s[2] / 2 + dz - 20;
            for (int y = 2; y < s[1] - 8; y++) {
                boolean floor = cells.cells[(z * s[1] + y - 1) * s[0] + x] != 0;
                boolean air = true;
                for (int k = 0; k < 4; k++) air &= cells.cells[(z * s[1] + y + k) * s[0] + x] == 0;
                boolean ceiling = false;
                for (int k = 4; k < 8; k++) ceiling |= cells.cells[(z * s[1] + y + k) * s[0] + x] != 0;
                if (floor && air && ceiling && cells.cells[(z * s[1] + y) * s[0] + x + 3] == 0) return new Vec3(x + 0.5, y + 1.5, z + 0.5);
            }
        }
        return null;
    }

    private static double[] envelope(Engine engine, SteamAudio.SimulationOutputs outputs, Vec3 direction, double[] edges) {
        double[] windows = new double[edges.length - 1];
        try (var renderer = new SteamRenderer(engine.context.getValue(), 48000)) {
            renderer.reflectionsReady();
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            int blocks = (int) (edges[edges.length - 1] * 48000 / SteamRenderer.FRAME);
            for (int block = 0; block < blocks; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 0) input[0] = 1;
                float[][] output = renderer.render(input, muted, outputs.reflections, direction, new SteamAudio.Space(), false, 1f);
                double time = block * (double) SteamRenderer.FRAME / 48000, energy = 0;
                for (float[] channel : output) for (float sample : channel) energy += sample * (double) sample;
                for (int w = 0; w < windows.length; w++) if (time >= edges[w] && time < edges[w + 1]) windows[w] += energy;
            }
        }
        return windows;
    }

    /** A hollow 24x6x6 box: a train car's worth of triangles. */
    private static AcousticMesh.Data structure() {
        int[] size = {24, 6, 6};
        byte[] cells = new byte[size[0] * size[1] * size[2]];
        for (int z = 0; z < size[2]; z++) for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++) {
            boolean shell = x == 0 || y == 0 || z == 0 || x == size[0] - 1 || y == size[1] - 1 || z == size[2] - 1;
            if (shell && (x * 7 + y * 3 + z) % 5 != 0) cells[(z * size[1] + y) * size[0] + x] = (byte) (1 + (x + z) % 4);
        }
        var mesh = new AcousticMesh(Vec3.ZERO);
        mesh.appendCells(cells, new int[]{0, 0, 0}, size);
        return mesh.data();
    }

    private static double median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1e6;
    }

    private static Cells load(String name, Path file) throws Exception {
        try (var in = new DataInputStream(Files.newInputStream(file))) {
            int[] size = {in.readInt(), in.readInt(), in.readInt()};
            byte[] cells = new byte[size[0] * size[1] * size[2]];
            in.readFully(cells);
            return new Cells(name, cells, size);
        }
    }

    /** Two blocks above the highest solid cell of column (x, z). */
    private static Vec3 above(Cells cells, int x, int z) {
        int[] s = cells.size;
        for (int y = s[1] - 1; y >= 0; y--) {
            if (cells.cells[(z * s[1] + y) * s[0] + x] != 0) return new Vec3(x + 0.5, Math.min(s[1] - 1, y + 2) + 0.5, z + 0.5);
        }
        return new Vec3(x + 0.5, s[1] / 2.0, z + 0.5);
    }

    /** The production reflection engine's setup with backend, threads and duration exposed. */
    private static final class Engine implements AutoCloseable {
        final Api api = api();
        final int type;
        final PointerByReference context = new PointerByReference(), scene = new PointerByReference(),
                simulator = new PointerByReference(), source = new PointerByReference(),
                openCL = new PointerByReference(), radeon = new PointerByReference(), embree = new PointerByReference(),
                subScene = new PointerByReference(), instance = new PointerByReference();
        final SteamAudio.SceneSettings sceneSettings = new SteamAudio.SceneSettings();
        SteamAudio.SceneSettings subSettings;
        SteamStaticMesh mesh, subMesh;
        float scattering = AcousticMaterials.GPU_SCATTERING;

        Engine(int type, int threads, float duration) { this(type, threads, duration, 1); }

        Engine(int type, int threads, float duration, int sources) {
            this.type = type;
            SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "context");
            sceneSettings.type = type;
            if (type == 2) {
                var list = new PointerByReference();
                SteamAudio.check(api.iplOpenCLDeviceListCreate(context.getValue(), new SteamGpu.DeviceSettings(), list), "GPU enumeration");
                try {
                    SteamAudio.check(api.iplOpenCLDeviceCreate(context.getValue(), list.getValue(), 0, openCL), "GPU device");
                    SteamAudio.check(api.iplRadeonRaysDeviceCreate(openCL.getValue(), null, radeon), "GPU ray tracer");
                } finally { api.iplOpenCLDeviceListRelease(list); }
                sceneSettings.radeon = radeon.getValue();
            } else {
                SteamAudio.check(api.iplEmbreeDeviceCreate(context.getValue(), null, embree), "embree");
                sceneSettings.embree = embree.getValue();
            }
            SteamAudio.check(api.iplSceneCreate(context.getValue(), sceneSettings, scene), "scene");
            api.iplSceneCommit(scene.getValue());
            var settings = new SteamAudio.SimulationSettings();
            settings.flags = SteamSimulation.REFLECTIONS;
            settings.samplingRate = 48000;
            settings.order = 1;
            settings.duration = duration;
            settings.maxRays = SteamSimulation.GPU_RAYS;
            settings.sceneType = type;
            settings.threads = threads;
            settings.sources = sources;
            settings.openCL = openCL.getValue();
            settings.radeon = radeon.getValue();
            SteamAudio.check(api.iplSimulatorCreate(context.getValue(), settings, simulator), "simulator");
            api.iplSimulatorSetScene(simulator.getValue(), scene.getValue());
            var sourceSettings = new SteamAudio.SourceSettings();
            sourceSettings.flags = SteamSimulation.REFLECTIONS;
            SteamAudio.check(api.iplSourceCreate(simulator.getValue(), sourceSettings, source), "source");
            api.iplSourceAdd(source.getValue(), simulator.getValue());
            api.iplSimulatorCommit(simulator.getValue());
        }

        void upload(AcousticMesh.Data data, long[][] phases, int i) {
            long start = System.nanoTime();
            if (mesh != null) mesh.close();
            mesh = new SteamStaticMesh(scene.getValue(), data, scattering);
            long created = System.nanoTime();
            api.iplSceneCommit(scene.getValue());
            long committed = System.nanoTime();
            api.iplSimulatorCommit(simulator.getValue());
            long end = System.nanoTime();
            phases[0][i] = created - start;
            phases[1][i] = committed - created;
            phases[2][i] = end - committed;
        }

        void addInstance(AcousticMesh.Data structure, Vec3 at) {
            subSettings = new SteamAudio.SceneSettings();
            subSettings.type = type;
            subSettings.embree = embree.getValue();
            SteamAudio.check(api.iplSceneCreate(context.getValue(), subSettings, subScene), "sub-scene");
            subMesh = new SteamStaticMesh(subScene.getValue(), structure, AcousticMaterials.GPU_SCATTERING);
            api.iplSceneCommit(subScene.getValue());
            var settings = new InstancedMeshSettings();
            settings.subScene = subScene.getValue();
            Matrix.translation(settings.transform, at.x, at.y, at.z);
            SteamAudio.check(api.iplInstancedMeshCreate(scene.getValue(), settings, instance), "instanced mesh");
            api.iplInstancedMeshAdd(instance.getValue(), scene.getValue());
            api.iplSceneCommit(scene.getValue());
            api.iplSimulatorCommit(simulator.getValue());
        }

        void moveInstance(Vec3 at) {
            api.iplInstancedMeshUpdateTransform(instance.getValue(), scene.getValue(),
                    Matrix.translation(new Matrix.ByValue(), at.x, at.y, at.z));
            api.iplSceneCommit(scene.getValue());
            api.iplSimulatorCommit(simulator.getValue());
        }

        SteamAudio.SimulationOutputs run(Vec3 listener, Vec3 sourcePosition, int rays, int bounces, float duration) {
            var inputs = new SteamAudio.SimulationInputs();
            inputs.flags = SteamSimulation.REFLECTIONS;
            inputs.source.origin = new SteamAudio.Vector(sourcePosition.x, sourcePosition.y, sourcePosition.z);
            api.iplSourceSetInputs(source.getValue(), SteamSimulation.REFLECTIONS, inputs);
            var shared = new SteamAudio.SharedInputs();
            shared.rays = rays;
            shared.bounces = bounces;
            shared.duration = duration;
            shared.order = 1;
            shared.listener.origin = new SteamAudio.Vector(listener.x, listener.y, listener.z);
            api.iplSimulatorSetSharedInputs(simulator.getValue(), SteamSimulation.REFLECTIONS, shared);
            api.iplSimulatorRunReflections(simulator.getValue());
            var outputs = new SteamAudio.SimulationOutputs();
            api.iplSourceGetOutputs(source.getValue(), SteamSimulation.REFLECTIONS, outputs);
            return outputs;
        }

        @Override public void close() {
            api.iplSourceRemove(source.getValue(), simulator.getValue());
            api.iplSourceRelease(source);
            api.iplSimulatorRelease(simulator);
            if (instance.getValue() != null) {
                api.iplInstancedMeshRemove(instance.getValue(), scene.getValue());
                api.iplInstancedMeshRelease(instance);
            }
            if (subMesh != null) subMesh.close();
            if (subScene.getValue() != null) api.iplSceneRelease(subScene);
            if (mesh != null) mesh.close();
            api.iplSceneRelease(scene);
            if (radeon.getValue() != null) api.iplRadeonRaysDeviceRelease(radeon);
            if (openCL.getValue() != null) api.iplOpenCLDeviceRelease(openCL);
            if (embree.getValue() != null) api.iplEmbreeDeviceRelease(embree);
            api.iplContextRelease(context);
        }
    }
}
