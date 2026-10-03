package dimblend.radio.acoustics;

import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.PointerByReference;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** SCRATCH (not for commit): can Steam Audio 4.8.1 bake our scenes, and what does it cost? */
class SteamBakeFeasibilityScratchTest {
    interface BakeApi extends SteamGpu.Api {
        int iplEmbreeDeviceCreate(Pointer context, Pointer settings, PointerByReference device);
        void iplEmbreeDeviceRelease(PointerByReference device);
        int iplProbeArrayCreate(Pointer context, PointerByReference array);
        void iplProbeArrayGenerateProbes(Pointer array, Pointer scene, GenerationParams params);
        int iplProbeArrayGetNumProbes(Pointer array);
        SphereValue iplProbeArrayGetProbe(Pointer array, int index);
        void iplProbeArrayRelease(PointerByReference array);
        int iplProbeBatchCreate(Pointer context, PointerByReference batch);
        void iplProbeBatchAddProbe(Pointer batch, SphereValue probe);
        void iplProbeBatchCommit(Pointer batch);
        int iplProbeBatchGetNumProbes(Pointer batch);
        long iplProbeBatchGetDataSize(Pointer batch, SteamAudio.BakedId identifier);
        void iplProbeBatchRelease(PointerByReference batch);
        void iplReflectionsBakerBake(Pointer context, BakeParams params, Progress progress, Pointer user);
        void iplReflectionsBakerCancelBake(Pointer context);
        void iplSimulatorAddProbeBatch(Pointer simulator, Pointer batch);
        void iplSimulatorRemoveProbeBatch(Pointer simulator, Pointer batch);
        int iplSerializedObjectCreate(Pointer context, SerializedSettings settings, PointerByReference object);
        long iplSerializedObjectGetSize(Pointer object);
        Pointer iplSerializedObjectGetData(Pointer object);
        void iplSerializedObjectRelease(PointerByReference object);
        void iplProbeBatchSave(Pointer batch, Pointer object);
        int iplProbeBatchLoad(Pointer context, Pointer object, PointerByReference batch);
    }
    @FieldOrder({"data", "size"})
    public static class SerializedSettings extends Structure { public Pointer data; public long size; }
    interface Progress extends Callback { void invoke(float progress, Pointer user); }
    public static class SphereValue extends SteamAudio.Sphere implements Structure.ByValue { }
    @FieldOrder({"type", "spacing", "height", "transform"})
    public static class GenerationParams extends Structure {
        public int type = 1;
        public float spacing, height;
        public float[] transform = new float[16];
    }
    @FieldOrder({"scene", "probeBatch", "sceneType", "identifier", "bakeFlags", "numRays", "numDiffuseSamples",
            "numBounces", "simulatedDuration", "savedDuration", "order", "numThreads", "rayBatchSize",
            "irradianceMinDistance", "bakeBatchSize", "openCL", "radeon"})
    public static class BakeParams extends Structure {
        public Pointer scene, probeBatch;
        public int sceneType;
        public SteamAudio.BakedId identifier = new SteamAudio.BakedId();
        public int bakeFlags = 1, numRays, numDiffuseSamples = 128, numBounces;
        public float simulatedDuration, savedDuration;
        public int order = 1, numThreads = 1, rayBatchSize = 1;
        public float irradianceMinDistance = 1;
        public int bakeBatchSize = 1;
        public Pointer openCL, radeon;
    }

    private static final Path MESH = Path.of("E:/misc/dimblend/build/reverb-validation/worldmesh.bin");
    private static final Vec3 SOURCE_WORLD = new Vec3(221980.5, 69.5, -8.5);
    private static BakeApi api;
    private static PrintWriter log;

    private static AcousticMesh.Data loadMesh() throws Exception {
        try (var in = new java.io.DataInputStream(Files.newInputStream(MESH))) {
            Vec3 origin = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
            float[] vertices = new float[in.readInt()];
            for (int i = 0; i < vertices.length; i++) vertices[i] = in.readFloat();
            int[] triangles = new int[in.readInt()];
            for (int i = 0; i < triangles.length; i++) triangles[i] = in.readInt();
            int[] materials = new int[in.readInt()];
            for (int i = 0; i < materials.length; i++) materials[i] = Math.max(0, Math.min(AcousticMaterials.COUNT - 1, in.readInt()));
            return new AcousticMesh.Data(vertices, triangles, materials, origin);
        }
    }

    /** Native scene of one static mesh; keeps every native buffer reachable. */
    private static final class Scene implements AutoCloseable {
        final PointerByReference context = new PointerByReference(), scene = new PointerByReference(),
                mesh = new PointerByReference(), embree = new PointerByReference(), openCL = new PointerByReference(),
                radeon = new PointerByReference();
        final int type;
        final List<Object> keep = new ArrayList<>();

        Scene(int type, AcousticMesh.Data data) {
            this.type = type;
            SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "context");
            var settings = new SteamAudio.SceneSettings();
            settings.type = type;
            if (type == 1) {
                SteamAudio.check(api.iplEmbreeDeviceCreate(context.getValue(), null, embree), "embree");
                settings.embree = embree.getValue();
            } else if (type == 2) {
                var list = new PointerByReference();
                SteamAudio.check(api.iplOpenCLDeviceListCreate(context.getValue(), new SteamGpu.DeviceSettings(), list), "cl list");
                SteamAudio.check(api.iplOpenCLDeviceCreate(context.getValue(), list.getValue(), 0, openCL), "cl");
                api.iplOpenCLDeviceListRelease(list);
                SteamAudio.check(api.iplRadeonRaysDeviceCreate(openCL.getValue(), null, radeon), "rr");
                settings.radeon = radeon.getValue();
            }
            SteamAudio.check(api.iplSceneCreate(context.getValue(), settings, scene), "scene");
            keep.add(settings);
            var vertexData = new Memory(data.vertices().length * 4L);
            var triangleData = new Memory(data.triangles().length * 4L);
            var materialIndices = new Memory(data.materials().length * 4L);
            vertexData.write(0, data.vertices(), 0, data.vertices().length);
            triangleData.write(0, data.triangles(), 0, data.triangles().length);
            materialIndices.write(0, data.materials(), 0, data.materials().length);
            var materials = (SteamAudio.Material[]) new SteamAudio.Material().toArray(AcousticMaterials.COUNT);
            for (int i = 0; i < AcousticMaterials.COUNT; i++) {
                materials[i].absorption = AcousticMaterials.absorption(i);
                materials[i].scattering = AcousticMaterials.GPU_SCATTERING;
                materials[i].transmission = AcousticMaterials.transmission(i, 1);
                materials[i].write();
            }
            var mesh = new SteamGpu.MeshSettings();
            mesh.vertices = data.vertices().length / 3;
            mesh.triangles = data.triangles().length / 3;
            mesh.materials = AcousticMaterials.COUNT;
            mesh.vertexData = vertexData;
            mesh.triangleData = triangleData;
            mesh.materialIndices = materialIndices;
            mesh.materialData = materials[0].getPointer();
            SteamAudio.check(api.iplStaticMeshCreate(scene.getValue(), mesh, this.mesh), "mesh");
            api.iplStaticMeshAdd(this.mesh.getValue(), scene.getValue());
            api.iplSceneCommit(scene.getValue());
            keep.addAll(List.of(vertexData, triangleData, materialIndices, materials, mesh));
        }

        Pointer context() { return context.getValue(); }

        @Override public void close() {
            if (mesh.getValue() != null) api.iplStaticMeshRelease(mesh);
            if (scene.getValue() != null) api.iplSceneRelease(scene);
            if (embree.getValue() != null) api.iplEmbreeDeviceRelease(embree);
            if (radeon.getValue() != null) api.iplRadeonRaysDeviceRelease(radeon);
            if (openCL.getValue() != null) api.iplOpenCLDeviceRelease(openCL);
            api.iplContextRelease(context);
        }
    }

    private static SteamAudio.BakedId staticSource(Vec3 source, float radius) {
        var id = new SteamAudio.BakedId();
        id.type = 0;
        id.variation = 1;
        id.sphere.center.set(source.x, source.y, source.z);
        id.sphere.radius = radius;
        return id;
    }

    private static Pointer probes(Scene scene, Vec3 center, float half, float spacing, float height, List<Vec3> positions) {
        var array = new PointerByReference();
        SteamAudio.check(api.iplProbeArrayCreate(scene.context(), array), "probe array");
        var params = new GenerationParams();
        params.spacing = spacing;
        params.height = height;
        // Unit cube [0,1]^3 (Steam's generator volume) scaled and moved onto the box; translation in column 3.
        // Clipped to the hall's interior: the generator casts down from the volume's top.
        double x0 = Math.max(-23.9, center.x - half), x1 = Math.min(23.9, center.x + half);
        double y0 = Math.max(-5.9, center.y - half), y1 = Math.min(5.9, center.y + half);
        double z0 = Math.max(-15.9, center.z - half), z1 = Math.min(15.9, center.z + half);
        params.transform[0] = (float) (x1 - x0);
        params.transform[5] = (float) (y1 - y0);
        params.transform[10] = (float) (z1 - z0);
        params.transform[3] = (float) x0;
        params.transform[7] = (float) y0;
        params.transform[11] = (float) z0;
        params.transform[15] = 1;
        api.iplProbeArrayGenerateProbes(array.getValue(), scene.scene.getValue(), params);
        int count = api.iplProbeArrayGetNumProbes(array.getValue());
        var batch = new PointerByReference();
        SteamAudio.check(api.iplProbeBatchCreate(scene.context(), batch), "probe batch");
        for (int i = 0; i < count; i++) {
            SphereValue probe = api.iplProbeArrayGetProbe(array.getValue(), i);
            positions.add(new Vec3(probe.center.x, probe.center.y, probe.center.z));
            api.iplProbeBatchAddProbe(batch.getValue(), probe);
        }
        api.iplProbeBatchCommit(batch.getValue());
        api.iplProbeArrayRelease(array);
        return batch.getValue();
    }

    private static double bake(Scene scene, Pointer batch, SteamAudio.BakedId id, int rays, int bounces, float duration,
            int threads, int bakeBatch) {
        var params = new BakeParams();
        params.scene = scene.scene.getValue();
        params.probeBatch = batch;
        params.sceneType = scene.type;
        // Copy, never assign: an assigned embedded Structure is re-pointed into this parent's memory.
        params.identifier.type = id.type;
        params.identifier.variation = id.variation;
        params.identifier.sphere.center.set(id.sphere.center);
        params.identifier.sphere.radius = id.sphere.radius;
        params.numRays = rays;
        params.numBounces = bounces;
        params.simulatedDuration = duration;
        params.savedDuration = duration;
        params.numThreads = threads;
        params.bakeBatchSize = bakeBatch;
        params.openCL = scene.openCL.getValue();
        params.radeon = scene.radeon.getValue();
        Progress progress = (value, user) -> { };
        long start = System.nanoTime();
        api.iplReflectionsBakerBake(scene.context(), params, progress, null);
        double ms = (System.nanoTime() - start) / 1e6;
        java.lang.ref.Reference.reachabilityFence(progress);
        return ms;
    }

    /** Runtime simulator over the scene; {@code batch} non-null switches the source to baked data. */
    private static final class Runtime implements AutoCloseable {
        final PointerByReference simulator = new PointerByReference(), source = new PointerByReference();
        Runtime(Scene scene, Pointer batch) {
            var settings = new SteamAudio.SimulationSettings();
            settings.flags = 2;
            settings.sceneType = scene.type;
            settings.maxRays = 1024;
            settings.samplingRate = 44100;
            settings.order = 1;
            settings.duration = 6;
            settings.openCL = scene.openCL.getValue();
            settings.radeon = scene.radeon.getValue();
            SteamAudio.check(api.iplSimulatorCreate(scene.context(), settings, simulator), "simulator");
            api.iplSimulatorSetScene(simulator.getValue(), scene.scene.getValue());
            if (batch != null) api.iplSimulatorAddProbeBatch(simulator.getValue(), batch);
            var sourceSettings = new SteamAudio.SourceSettings();
            sourceSettings.flags = 2;
            SteamAudio.check(api.iplSourceCreate(simulator.getValue(), sourceSettings, source), "source");
            api.iplSourceAdd(source.getValue(), simulator.getValue());
            api.iplSimulatorCommit(simulator.getValue());
        }

        SteamAudio.SimulationOutputs run(Vec3 sourceLocal, Vec3 listenerLocal, SteamAudio.BakedId baked, int rays) {
            var inputs = new SteamAudio.SimulationInputs();
            inputs.flags = 2;
            inputs.source.origin.set(sourceLocal.x, sourceLocal.y, sourceLocal.z);
            if (baked != null) {
                inputs.baked = 1;
                inputs.bakedId.type = baked.type;
                inputs.bakedId.variation = baked.variation;
                inputs.bakedId.sphere.center.set(baked.sphere.center);
                inputs.bakedId.sphere.radius = baked.sphere.radius;
            }
            api.iplSourceSetInputs(source.getValue(), 2, inputs);
            var shared = new SteamAudio.SharedInputs();
            shared.listener.origin.set(listenerLocal.x, listenerLocal.y, listenerLocal.z);
            shared.rays = rays;
            shared.bounces = 128;
            shared.duration = 6;
            shared.order = 1;
            api.iplSimulatorSetSharedInputs(simulator.getValue(), 2, shared);
            api.iplSimulatorRunReflections(simulator.getValue());
            var outputs = new SteamAudio.SimulationOutputs();
            api.iplSourceGetOutputs(source.getValue(), 2, outputs);
            return outputs;
        }

        @Override public void close() {
            api.iplSourceRemove(source.getValue(), simulator.getValue());
            api.iplSourceRelease(source);
            api.iplSimulatorRelease(simulator);
        }
    }

    /** Energy of the reflections alone, rendered from a unit impulse. */
    private static double wet(Pointer context, SteamAudio.SimulationOutputs outputs, Vec3 relative) {
        if (outputs.reflections.ir == null) return -1;
        try (var renderer = new SteamRenderer(context, 44100)) {
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            double energy = 0;
            // Warm-up blocks with the same IR first; a fixed 1 m offset keeps the propagation line short.
            Vec3 near = new Vec3(1, 0, 0);
            for (int block = 0; block < 260; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 2) input[0] = 1;
                for (float[] channel : renderer.render(input, muted, outputs.reflections, near, new SteamAudio.Space(), false, 1f)) {
                    if (block >= 2) for (float sample : channel) energy += sample * (double) sample;
                }
            }
            java.lang.ref.Reference.reachabilityFence(outputs);
            return energy;
        }
    }

    private static void say(String format, Object... args) {
        String line = String.format(format, args);
        System.out.println(line);
        log.println(line);
        log.flush();
    }

    private static AcousticMesh.Data mesh;
    private static Vec3 source;

    private static synchronized void setUp() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        assumeTrue(Files.exists(MESH), "no dumped mesh");
        if (api != null) return;
        SteamGpu.api();
        api = Native.load(SteamAudio.library().toString(), BakeApi.class);
        log = new PrintWriter(Files.newBufferedWriter(Path.of("bake-feasibility.txt"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND));
        // A stone hall with pillars; the dumped world mesh gives no wet field even on the production engine.
        mesh = hall();
        source = new Vec3(-16, -4.5, 0);
        say("synthetic hall: %d triangles, source %s", mesh.triangleCount(), source);
    }

    /** Axis-aligned box faces; inward faces for the hall, outward for pillars. */
    private static void box(List<float[]> quads, double x0, double y0, double z0, double x1, double y1, double z1, boolean inward) {
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] faces = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 7, 6, 2}, {0, 4, 7, 3}, {1, 2, 6, 5}};
        for (int[] f : faces) {
            float[] q = new float[12];
            for (int k = 0; k < 4; k++) {
                int corner = f[inward ? 3 - k : k];
                for (int a = 0; a < 3; a++) q[k * 3 + a] = (float) c[corner][a];
            }
            quads.add(q);
        }
    }

    private static AcousticMesh.Data hall() {
        List<float[]> quads = new ArrayList<>();
        box(quads, -24, -6, -16, 24, 6, 16, true);
        for (int i = 0; i < 6; i++) {
            double x = -12 + (i % 3) * 12, z = i < 3 ? -6 : 6;
            box(quads, x - 1, -6, z - 1, x + 1, 6, z + 1, false);
        }
        float[] vertices = new float[quads.size() * 12];
        int[] triangles = new int[quads.size() * 6];
        int[] materials = new int[quads.size() * 2];
        java.util.Arrays.fill(materials, AcousticMaterials.STONE);
        for (int q = 0; q < quads.size(); q++) {
            System.arraycopy(quads.get(q), 0, vertices, q * 12, 12);
            int b = q * 4;
            int[] tri = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(tri, 0, triangles, q * 6, 6);
        }
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    private static String fields(SteamAudio.SimulationOutputs out) {
        var r = out.reflections;
        return "type=" + r.type + " ir=" + r.ir + " irSize=" + r.irSize + " channels=" + r.channels + " delay=" + r.delay;
    }

    private static String tryWet(Pointer context, SteamAudio.SimulationOutputs out, Vec3 relative) {
        try { return String.format("%.4g", wet(context, out, relative)); }
        catch (Throwable error) { return "render failed: " + error; }
    }

    /** Bakes a 24 m box around the source; returns its probes. */
    private static Pointer smallBake(Scene scene, SteamAudio.BakedId id, float duration, List<Vec3> positions) {
        Pointer batch = probes(scene, Vec3.ZERO, 30, 2, 1.5f, positions);
        double ms = bake(scene, batch, id, 4096, 128, duration, 8, 1);
        say("  baked %d probes, %.1f s saved: %.0f ms, data %d bytes", positions.size(), duration, ms,
                api.iplProbeBatchGetDataSize(batch, id));
        return batch;
    }

    @Test void runtimeLookupOnCpuSimulator() throws Exception {
        setUp();
        say("== runtimeLookupOnCpuSimulator");
        try (Scene scene = new Scene(1, mesh)) {
            List<Vec3> positions = new ArrayList<>();
            SteamAudio.BakedId id = staticSource(source, 64);
            Pointer batch = smallBake(scene, id, 6f, positions);
            try (Runtime baked = new Runtime(scene, batch); Runtime live = new Runtime(scene, null)) {
                for (int i = 0; i < positions.size(); i += Math.max(1, positions.size() / 5)) {
                    Vec3 listener = positions.get(i).add(0.4, 0, 0.3);
                    Vec3 relative = source.subtract(listener);
                    long b0 = System.nanoTime();
                    var fromBake = baked.run(source, listener, id, 1024);
                    double bakedMs = (System.nanoTime() - b0) / 1e6;
                    long l0 = System.nanoTime();
                    var fromLive = live.run(source, listener, null, 1024);
                    double liveMs = (System.nanoTime() - l0) / 1e6;
                    say("  listener %.1f m: baked %.2f ms [%s] | live %.2f ms [%s]", relative.length(), bakedMs, fields(fromBake),
                            liveMs, fields(fromLive));
                    say("    wet baked=%s live=%s", tryWet(scene.context(), fromBake, relative), tryWet(scene.context(), fromLive, relative));
                }
                Vec3 far = source.add(40, 30, 40);
                var outside = baked.run(source, far, id, 1024);
                say("  listener outside every probe: [%s] wet=%s", fields(outside), tryWet(scene.context(), outside, source.subtract(far)));
                api.iplSimulatorRemoveProbeBatch(baked.simulator.getValue(), batch);
            }
            api.iplProbeBatchRelease(new PointerByReference(batch));
        }
    }

    @Test void crossContextLookupOnGpuSimulator() throws Exception {
        setUp();
        say("== crossContextLookupOnGpuSimulator");
        byte[] bytes;
        List<Vec3> positions = new ArrayList<>();
        SteamAudio.BakedId id = staticSource(source, 64);
        try (Scene cpu = new Scene(1, mesh)) {
            Pointer batch = smallBake(cpu, id, 6f, positions);
            var object = new PointerByReference();
            SteamAudio.check(api.iplSerializedObjectCreate(cpu.context(), new SerializedSettings(), object), "serialized");
            long s0 = System.nanoTime();
            api.iplProbeBatchSave(batch, object.getValue());
            bytes = api.iplSerializedObjectGetData(object.getValue()).getByteArray(0, (int) api.iplSerializedObjectGetSize(object.getValue()));
            say("  saved %d bytes in %.1f ms (gzip %d bytes)", bytes.length, (System.nanoTime() - s0) / 1e6, gzip(bytes));
            api.iplSerializedObjectRelease(object);
            api.iplProbeBatchRelease(new PointerByReference(batch));
        }
        try (Scene gpu = new Scene(2, mesh)) {
            var data = new Memory(bytes.length);
            data.write(0, bytes, 0, bytes.length);
            var settings = new SerializedSettings();
            settings.data = data;
            settings.size = bytes.length;
            var object = new PointerByReference();
            SteamAudio.check(api.iplSerializedObjectCreate(gpu.context(), settings, object), "serialized");
            var loaded = new PointerByReference();
            long l0 = System.nanoTime();
            SteamAudio.check(api.iplProbeBatchLoad(gpu.context(), object.getValue(), loaded), "probe batch load");
            api.iplProbeBatchCommit(loaded.getValue());
            say("  loaded %d probes in %.1f ms, data %d bytes", api.iplProbeBatchGetNumProbes(loaded.getValue()),
                    (System.nanoTime() - l0) / 1e6, api.iplProbeBatchGetDataSize(loaded.getValue(), id));
            try (Runtime baked = new Runtime(gpu, loaded.getValue()); Runtime live = new Runtime(gpu, null)) {
                for (int i = 0; i < positions.size(); i += Math.max(1, positions.size() / 4)) {
                    Vec3 listener = positions.get(i).add(0.4, 0, 0.3);
                    Vec3 relative = source.subtract(listener);
                    long b0 = System.nanoTime();
                    var fromBake = baked.run(source, listener, id, 1024);
                    double bakedMs = (System.nanoTime() - b0) / 1e6;
                    long g0 = System.nanoTime();
                    var fromLive = live.run(source, listener, null, 1024);
                    double liveMs = (System.nanoTime() - g0) / 1e6;
                    say("  GPU simulator, listener %.1f m: baked %.2f ms [%s] | live %.2f ms [%s]", relative.length(), bakedMs,
                            fields(fromBake), liveMs, fields(fromLive));
                    say("    wet baked=%s live=%s", tryWet(gpu.context(), fromBake, relative), tryWet(gpu.context(), fromLive, relative));
                }
                api.iplSimulatorRemoveProbeBatch(baked.simulator.getValue(), loaded.getValue());
            }
            api.iplProbeBatchRelease(loaded);
            api.iplSerializedObjectRelease(object);
        }
    }

    private static int gzip(byte[] bytes) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.GZIPOutputStream(out)) { zip.write(bytes); }
        return out.size();
    }

    @Test void sixSecondsAndRadeonBakeAndCancel() throws Exception {
        setUp();
        say("== sixSecondsAndRadeonBakeAndCancel");
        try (Scene scene = new Scene(1, mesh)) {
            List<Vec3> positions = new ArrayList<>();
            SteamAudio.BakedId id = staticSource(source, 64);
            Pointer batch = smallBake(scene, id, 6f, positions);
            AtomicReference<Double> took = new AtomicReference<>();
            Thread worker = new Thread(() -> took.set(bake(scene, batch, id, 8192, 128, 2f, 4, 1)));
            worker.start();
            Thread.sleep(300);
            long c0 = System.nanoTime();
            api.iplReflectionsBakerCancelBake(scene.context());
            worker.join();
            say("  [Embree] cancel: bake returned %.0f ms after cancel (ran %.0f ms)", (System.nanoTime() - c0) / 1e6, took.get());
            api.iplProbeBatchRelease(new PointerByReference(batch));
        }
        try (Scene scene = new Scene(2, mesh)) {
            List<Vec3> positions = new ArrayList<>();
            Pointer batch = probes(scene, source, 12, 2, 1.5f, positions);
            SteamAudio.BakedId id = staticSource(source, 64);
            for (int size : new int[] {1, 8, 32}) {
                double ms = bake(scene, batch, id, 1024, 64, 2f, 1, size);
                say("  [RadeonRays] %d probes, bakeBatch=%d: %.0f ms, %.1f ms/probe, data %d", positions.size(), size, ms,
                        ms / positions.size(), api.iplProbeBatchGetDataSize(batch, id));
            }
            AtomicReference<Double> took = new AtomicReference<>();
            Thread worker = new Thread(() -> took.set(bake(scene, batch, id, 8192, 128, 2f, 1, 8)));
            worker.start();
            Thread.sleep(300);
            long c0 = System.nanoTime();
            api.iplReflectionsBakerCancelBake(scene.context());
            worker.join();
            say("  [RadeonRays] cancel: bake returned %.0f ms after cancel (ran %.0f ms)", (System.nanoTime() - c0) / 1e6, took.get());
            api.iplProbeBatchRelease(new PointerByReference(batch));
        }
    }

    @Test void bakeAlongsideRealtime() throws Exception {
        setUp();
        say("== bakeAlongsideRealtime");
        try (var realtime = new SteamSimulation(44100, 2, true)) {
            Vec3 listenerWorld = mesh.origin().add(source).add(2, 0, 1), sourceWorld = mesh.origin().add(source);
            realtime.simulateGpu(mesh, listenerWorld, sourceWorld, 1024, 128);
            long r0 = System.nanoTime();
            for (int i = 0; i < 20; i++) realtime.simulateGpu(mesh, listenerWorld.add(0, 0, i * 0.1), sourceWorld, 1024, 128);
            double alone = (System.nanoTime() - r0) / 1e6 / 20;
            try (Scene scene = new Scene(1, mesh)) {
                List<Vec3> positions = new ArrayList<>();
                Pointer batch = probes(scene, source, 24, 2, 1.5f, positions);
                SteamAudio.BakedId id = staticSource(source, 64);
                AtomicReference<Double> took = new AtomicReference<>();
                Thread worker = new Thread(() -> took.set(bake(scene, batch, id, 1024, 64, 2f, 4, 1)));
                worker.start();
                int runs = 0, missing = 0;
                long c0 = System.nanoTime();
                while (worker.isAlive()) {
                    var out = realtime.simulateGpu(mesh, listenerWorld.add(0, 0, (runs % 20) * 0.1), sourceWorld, 1024, 128);
                    if (out.reflections.ir == null) missing++;
                    runs++;
                }
                worker.join();
                say("  GPU real-time alone %.1f ms/update; during a 4-thread Embree bake (%d probes, %.0f ms): %d updates at %.1f ms, %d without IR",
                        alone, positions.size(), took.get(), runs, (System.nanoTime() - c0) / 1e6 / Math.max(1, runs), missing);
                api.iplProbeBatchRelease(new PointerByReference(batch));
            }
        }
    }

    @Test void measurementSanity() throws Exception {
        setUp();
        say("== measurementSanity");
        Vec3 listenerWorld = mesh.origin().add(source).add(2, 0, 1), sourceWorld = mesh.origin().add(source);
        try (var production = new SteamSimulation(44100, 2, true)) {
            var out = production.simulateGpu(mesh, listenerWorld, sourceWorld, 1024, 128);
            say("  production GPU engine on the world mesh: [%s] wet=%s", fields(out), tryWet(production.context(), out, new Vec3(-2, 0, -1)));
        }
        float[] v = {-8,-8,-8,8,-8,-8,8,8,-8,-8,8,-8,-8,-8,8,8,-8,8,8,8,8,-8,8,8};
        int[] t = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        int[] m = new int[12];
        java.util.Arrays.fill(m, AcousticMaterials.STONE);
        var box = new AcousticMesh.Data(v, t, m, Vec3.ZERO);
        try (var production = new SteamSimulation(44100, 2, true)) {
            var out = production.simulateGpu(box, Vec3.ZERO, new Vec3(4, 0, 0), 1024, 128);
            say("  production GPU engine in a stone box: [%s] wet=%s", fields(out), tryWet(production.context(), out, new Vec3(4, 0, 0)));
        }
        for (int type : new int[] {1, 2}) {
            try (Scene scene = new Scene(type, box); Runtime live = new Runtime(scene, null)) {
                var out = live.run(new Vec3(4, 0, 0), Vec3.ZERO, null, 1024);
                say("  test simulator type %d in a stone box: [%s] wet=%s", type, fields(out), tryWet(scene.context(), out, new Vec3(4, 0, 0)));
            }
        }
    }
}
