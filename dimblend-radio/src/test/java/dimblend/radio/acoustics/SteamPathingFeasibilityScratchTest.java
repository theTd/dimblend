package dimblend.radio.acoustics;

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
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** SCRATCH (not for commit): Steam Audio pathing (baked diffraction) cost and behaviour. */
class SteamPathingFeasibilityScratchTest {
    interface PathApi extends SteamBakeFeasibilityScratchTest.BakeApi {
        void iplPathBakerBake(Pointer context, PathBakeParams params, SteamBakeFeasibilityScratchTest.Progress progress, Pointer user);
        void iplPathBakerCancelBake(Pointer context);
        void iplSimulatorRunPathing(Pointer simulator);
        int iplPathEffectCreate(Pointer context, SteamAudio.AudioSettings audio, PathEffectSettings settings, PointerByReference effect);
        int iplPathEffectApply(Pointer effect, SteamAudio.PathParams params, SteamAudio.AudioBuffer in, SteamAudio.AudioBuffer out);
        void iplPathEffectReset(Pointer effect);
        void iplPathEffectRelease(PointerByReference effect);
    }
    @FieldOrder({"scene", "probeBatch", "identifier", "numSamples", "radius", "threshold", "visRange", "pathRange", "numThreads"})
    public static class PathBakeParams extends Structure {
        public Pointer scene, probeBatch;
        public SteamAudio.BakedId identifier = new SteamAudio.BakedId();
        public int numSamples;
        public float radius, threshold, visRange, pathRange;
        public int numThreads;
    }
    @FieldOrder({"maxOrder", "spatialize", "layout", "hrtf"})
    public static class PathEffectSettings extends Structure {
        public int maxOrder = 1, spatialize;
        public SteamAudio.SpeakerLayout layout = new SteamAudio.SpeakerLayout();
        public Pointer hrtf;
    }
    @FieldOrder({"type", "callback", "user"})
    public static class Deviation extends Structure { public int type; public Pointer callback, user; }

    private static PathApi api;
    private static PrintWriter log;

    private static void say(String format, Object... args) {
        String line = String.format(format, args);
        System.out.println(line);
        log.println(line);
        log.flush();
    }

    /** Hall 48 x 12 x 32 with a partition at x = 0 from z = -16 to z = 8: an 8 m gap at the +z end. */
    static List<double[]> partitionedHall(boolean gapClosed) {
        List<double[]> boxes = partitionedHall(24, 16, 1);
        if (gapClosed) boxes.add(new double[] {-0.5, -6, 8, 0.5, 6, 16, 0});
        return boxes;
    }

    /** Hall of half extents hx, hz with a partition at x = 0 leaving an 8 m gap at the +z end. */
    static List<double[]> partitionedHall(double hx, double hz, int unused) {
        List<double[]> boxes = new ArrayList<>();
        boxes.add(new double[] {-hx, -6, -hz, hx, 6, hz, 1});
        boxes.add(new double[] {-0.5, -6, -hz, 0.5, 6, hz - 8, 0});
        return boxes;
    }

    /** The 48 x 32 hall with the +z gap walled up and an opening at z in [-16, -8] instead. */
    static List<double[]> movedGap() {
        List<double[]> boxes = new ArrayList<>();
        boxes.add(new double[] {-24, -6, -16, 24, 6, 16, 1});
        boxes.add(new double[] {-0.5, -6, -8, 0.5, 6, 16, 0});
        return boxes;
    }

    static AcousticMesh.Data mesh(List<double[]> boxes) {
        List<float[]> quads = new ArrayList<>();
        for (double[] b : boxes) {
            double[][] c = {{b[0], b[1], b[2]}, {b[3], b[1], b[2]}, {b[3], b[4], b[2]}, {b[0], b[4], b[2]},
                    {b[0], b[1], b[5]}, {b[3], b[1], b[5]}, {b[3], b[4], b[5]}, {b[0], b[4], b[5]}};
            int[][] faces = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 7, 6, 2}, {0, 4, 7, 3}, {1, 2, 6, 5}};
            boolean inward = b[6] > 0;
            for (int[] f : faces) {
                float[] q = new float[12];
                for (int k = 0; k < 4; k++) {
                    int corner = f[inward ? 3 - k : k];
                    for (int a = 0; a < 3; a++) q[k * 3 + a] = (float) c[corner][a];
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
            int b = q * 4;
            System.arraycopy(new int[] {b, b + 1, b + 2, b, b + 2, b + 3}, 0, triangles, q * 6, 6);
        }
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    /** Java ray caster over the same boxes, for a custom-callback scene like the direct worker's. */
    static AcousticRay cast(List<double[]> boxes, Vec3 from, Vec3 to) {
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
    }

    private static final class PathRuntime implements AutoCloseable {
        final PointerByReference simulator = new PointerByReference(), source = new PointerByReference();
        final Deviation deviation = new Deviation();
        PathRuntime(Pointer context, Pointer scene, int sceneType, Pointer batch) {
            var settings = new SteamAudio.SimulationSettings();
            settings.flags = 4;
            settings.sceneType = sceneType;
            settings.samplingRate = 44100;
            settings.order = 1;
            settings.visSamples = 4;
            SteamAudio.check(api.iplSimulatorCreate(context, settings, simulator), "simulator");
            api.iplSimulatorSetScene(simulator.getValue(), scene);
            api.iplSimulatorAddProbeBatch(simulator.getValue(), batch);
            var sourceSettings = new SteamAudio.SourceSettings();
            sourceSettings.flags = 4;
            SteamAudio.check(api.iplSourceCreate(simulator.getValue(), sourceSettings, source), "source");
            api.iplSourceAdd(source.getValue(), simulator.getValue());
            api.iplSimulatorCommit(simulator.getValue());
            deviation.write();
        }

        SteamAudio.SimulationOutputs run(Pointer batch, Vec3 sourceAt, Vec3 listener, boolean validate, boolean alternate) {
            var inputs = new SteamAudio.SimulationInputs();
            inputs.flags = 4;
            inputs.source.origin.set(sourceAt.x, sourceAt.y, sourceAt.z);
            inputs.probes = batch;
            inputs.visRadius = 1;
            inputs.visThreshold = 0.1f;
            inputs.visRange = 50;
            inputs.pathOrder = 1;
            inputs.validation = validate ? 1 : 0;
            inputs.alternate = alternate ? 1 : 0;
            inputs.deviation = deviation.getPointer();
            api.iplSourceSetInputs(source.getValue(), 4, inputs);
            var shared = new SteamAudio.SharedInputs();
            shared.listener.origin.set(listener.x, listener.y, listener.z);
            api.iplSimulatorSetSharedInputs(simulator.getValue(), 4, shared);
            api.iplSimulatorRunPathing(simulator.getValue());
            var outputs = new SteamAudio.SimulationOutputs();
            api.iplSourceGetOutputs(source.getValue(), 4, outputs);
            return outputs;
        }

        @Override public void close() {
            api.iplSourceRemove(source.getValue(), simulator.getValue());
            api.iplSourceRelease(source);
            api.iplSimulatorRelease(simulator);
        }
    }

    private static String describe(SteamAudio.SimulationOutputs out) {
        var p = out.pathing;
        float[] sh = p.coefficients == null ? new float[4] : p.coefficients.getFloatArray(0, 4);
        double energy = sh[0] * sh[0] + sh[1] * sh[1] + sh[2] * sh[2] + sh[3] * sh[3];
        return String.format("eq=[%.3f %.3f %.3f] sh=[%.3f %.3f %.3f %.3f] |sh|=%.3f", p.eq[0], p.eq[1], p.eq[2], sh[0], sh[1], sh[2], sh[3], Math.sqrt(energy));
    }

    @Test void pathingFeasibility() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        SteamGpu.api();
        api = Native.load(SteamAudio.library().toString(), PathApi.class);
        log = new PrintWriter(Files.newBufferedWriter(Path.of("pathing-feasibility.txt")));
        var open = partitionedHall(false);
        var closed = partitionedHall(true);
        Vec3 source = new Vec3(-16, -4.5, 4);
        // Embree scenes in one context: the bake scene (gap open) and a runtime scene with the gap walled up.
        var context = new PointerByReference();
        SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "context");
        var embree = new PointerByReference();
        SteamAudio.check(api.iplEmbreeDeviceCreate(context.getValue(), null, embree), "embree");
        List<Object> keep = new ArrayList<>();
        Pointer openScene = embreeScene(context.getValue(), embree.getValue(), mesh(open), keep);
        Pointer closedScene = embreeScene(context.getValue(), embree.getValue(), mesh(closed), keep);

        // Scaling: bake time and data size against probe count (open hall plus the partition).
        float[][] sizes = {{48, 32, 4}, {48, 32, 3}, {48, 32, 2}, {96, 64, 2}, {96, 96, 2}, {192, 192, 4}, {192, 192, 3}};
        for (float[] size : sizes) {
            List<double[]> boxes = partitionedHall(size[0] / 2, size[1] / 2, 1);
            Pointer scene = embreeScene(context.getValue(), embree.getValue(), mesh(boxes), keep);
            for (float visRange : size[0] > 48 ? new float[] {50, 24} : new float[] {50}) {
                List<Vec3> positions = new ArrayList<>();
                Pointer batch = floorProbes(context.getValue(), scene, size[2], size[0], size[1], positions);
                long b0 = System.nanoTime();
                bakePathing(context.getValue(), scene, batch, visRange, 100);
                double ms = (System.nanoTime() - b0) / 1e6;
                long bytes = api.iplProbeBatchGetDataSize(batch, pathingId());
                say("pathing bake %3.0f x %3.0f m, spacing %.0f m, visRange %.0f: %5d probes, %8.0f ms, data %9d bytes (%.1f KiB/probe, %.2f B/pair)",
                        size[0], size[1], size[2], visRange, positions.size(), ms, bytes, bytes / 1024.0 / positions.size(),
                        bytes / (double) positions.size() / positions.size());
                api.iplProbeBatchRelease(new PointerByReference(batch));
            }
        }

        // Behaviour in the 48 x 32 hall at 2 m spacing.
        List<Vec3> positions = new ArrayList<>();
        Pointer batch = floorProbes(context.getValue(), openScene, 2, 48, 32, positions);
        bakePathing(context.getValue(), openScene, batch, 50, 100);
        var audio = new SteamAudio.AudioSettings();
        audio.samplingRate = 44100;
        var effect = new PointerByReference();
        SteamAudio.check(api.iplPathEffectCreate(context.getValue(), audio, new PathEffectSettings(), effect), "path effect");
        try (PathRuntime onOpen = new PathRuntime(context.getValue(), openScene, 1, batch)) {
            // Walk behind the partition: occluded for z < ~9.8, in view through the gap beyond.
            for (double z = -14; z <= 14; z += 4) {
                Vec3 listener = new Vec3(8, -4.5, z);
                boolean visible = cast(open, listener, source).kind() == AcousticRay.Kind.MISS;
                long r0 = System.nanoTime();
                var out = onOpen.run(batch, source, listener, true, false);
                double runMs = (System.nanoTime() - r0) / 1e6;
                double[] gain = render(effect.getValue(), out);
                double direct = 1 / listener.distanceTo(source);
                say("  z=%5.1f %s %5.2f ms %s | path effect rms gain W %.4f Y %.4f Z %.4f X %.4f, 1/r %.4f, W/(1/r) %.3f",
                        z, visible ? "visible " : "occluded", runMs, describe(out), gain[0], gain[1], gain[2], gain[3], direct, gain[0] / direct);
            }
        }
        // Validation after the gap is walled up (no other route), run by run.
        try (PathRuntime onClosed = new PathRuntime(context.getValue(), closedScene, 1, batch)) {
            Vec3 listener = new Vec3(8, -4.5, -6);
            boolean[][] sequence = {{false, false}, {true, false}, {false, false}, {true, false}, {false, false},
                    {true, true}, {true, true}, {false, false}};
            for (boolean[] step : sequence) {
                long r0 = System.nanoTime();
                var out = onClosed.run(batch, source, listener, step[0], step[1]);
                say("  sealed: validate=%s alternate=%s %6.2f ms %s", step[0], step[1], (System.nanoTime() - r0) / 1e6, describe(out));
            }
        }
        // Alternate route: the +z gap walled up, a new opening cut at the -z end.
        Pointer movedScene = embreeScene(context.getValue(), embree.getValue(), mesh(movedGap()), keep);
        try (PathRuntime onMoved = new PathRuntime(context.getValue(), movedScene, 1, batch)) {
            Vec3 listener = new Vec3(8, -4.5, 2);
            boolean[][] sequence = {{false, false}, {true, false}, {true, true}, {true, true}, {true, true}, {false, false}};
            for (boolean[] step : sequence) {
                long r0 = System.nanoTime();
                var out = onMoved.run(batch, source, listener, step[0], step[1]);
                say("  moved gap: validate=%s alternate=%s %6.2f ms %s", step[0], step[1], (System.nanoTime() - r0) / 1e6, describe(out));
            }
        }
        // The custom-callback scene (as the direct worker uses) for validation rays.
        List<Object> callbacks = new ArrayList<>();
        Pointer customScene = customScene(context.getValue(), open, callbacks);
        try (PathRuntime onCustom = new PathRuntime(context.getValue(), customScene, 3, batch)) {
            double total = 0;
            int runs = 0;
            for (double z = -14; z <= 14; z += 4) {
                long r0 = System.nanoTime();
                var out = onCustom.run(batch, source, new Vec3(8, -4.5, z), true, false);
                total += (System.nanoTime() - r0) / 1e6;
                runs++;
                if (z == -6) say("  custom scene z=-6: %s", describe(out));
            }
            say("  custom-callback scene, validation on: %.2f ms per run", total / runs);
        }
        api.iplPathEffectRelease(effect);
        api.iplProbeBatchRelease(new PointerByReference(batch));
        log.close();
    }

    /** The hall split fully at x = 0 except for a 1 x 2 m doorway at z in [0, 1] on the floor. */
    static List<double[]> doorwayHall() {
        List<double[]> boxes = new ArrayList<>();
        boxes.add(new double[] {-24, -6, -16, 24, 6, 16, 1});
        boxes.add(new double[] {-0.5, -6, -16, 0.5, 6, 0, 0});
        boxes.add(new double[] {-0.5, -6, 1, 0.5, 6, 16, 0});
        boxes.add(new double[] {-0.5, -4, 0, 0.5, 6, 1, 0});
        return boxes;
    }

    @Test void doorwayCancelAndThreads() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        SteamGpu.api();
        api = Native.load(SteamAudio.library().toString(), PathApi.class);
        log = new PrintWriter(Files.newBufferedWriter(Path.of("pathing-doorway.txt")));
        var context = new PointerByReference();
        SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "context");
        var embree = new PointerByReference();
        SteamAudio.check(api.iplEmbreeDeviceCreate(context.getValue(), null, embree), "embree");
        List<Object> keep = new ArrayList<>();
        var door = doorwayHall();
        Pointer doorScene = embreeScene(context.getValue(), embree.getValue(), mesh(door), keep);
        Vec3 source = new Vec3(-16, -4.5, 4);
        Vec3[] listeners = {new Vec3(8, -4.5, -6), new Vec3(4, -4.5, 8), new Vec3(2, -4.5, 0.5), new Vec3(12, -4.5, 12)};
        for (float spacing : new float[] {4, 2}) {
            for (boolean doorProbe : new boolean[] {false, true}) {
                for (float radius : new float[] {1, 0.4f}) {
                    List<Vec3> positions = new ArrayList<>();
                    List<Vec3> extra = doorProbe ? List.of(new Vec3(0, -4.5, 0.5)) : List.of();
                    Pointer batch = floorProbes(context.getValue(), doorScene, spacing, 48, 32, positions, extra);
                    var params = new PathBakeParams();
                    params.scene = doorScene;
                    params.probeBatch = batch;
                    params.identifier.type = 1;
                    params.identifier.variation = 3;
                    params.numSamples = 4;
                    params.radius = radius;
                    params.threshold = 0.1f;
                    params.visRange = 50;
                    params.pathRange = 100;
                    params.numThreads = 8;
                    SteamBakeFeasibilityScratchTest.Progress progress = (value, user) -> { };
                    api.iplPathBakerBake(context.getValue(), params, progress, null);
                    StringBuilder line = new StringBuilder();
                    try (PathRuntime runtime = new PathRuntime(context.getValue(), doorScene, 1, batch)) {
                        for (Vec3 listener : listeners) {
                            var out = runtime.run(batch, source, listener, false, false);
                            float[] sh = out.pathing.coefficients == null ? new float[4] : out.pathing.coefficients.getFloatArray(0, 4);
                            line.append(String.format(" | L(%.0f,%.1f): eq %.2f/%.2f/%.2f W %.4f", listener.x, listener.z,
                                    out.pathing.eq[0], out.pathing.eq[1], out.pathing.eq[2], sh[0]));
                        }
                    }
                    say("doorway, spacing %.0f m (probe radius %.1f), door probe %s, vis radius %.1f, %d probes%s", spacing, generatedRadius,
                            doorProbe, radius, positions.size(), line);
                    api.iplProbeBatchRelease(new PointerByReference(batch));
                }
            }
        }
        // Threads and cancel on the 192 x 192 m hall at 4 m spacing.
        var big = partitionedHall(96, 96, 1);
        Pointer bigScene = embreeScene(context.getValue(), embree.getValue(), mesh(big), keep);
        for (int threads : new int[] {1, 2, 4}) {
            List<Vec3> positions = new ArrayList<>();
            Pointer batch = floorProbes(context.getValue(), bigScene, 4, 192, 192, positions);
            var params = new PathBakeParams();
            params.scene = bigScene;
            params.probeBatch = batch;
            params.identifier.type = 1;
            params.identifier.variation = 3;
            params.numSamples = 4;
            params.radius = 1;
            params.threshold = 0.1f;
            params.visRange = 50;
            params.pathRange = 100;
            params.numThreads = threads;
            float[] last = {0};
            SteamBakeFeasibilityScratchTest.Progress progress = (value, user) -> last[0] = value;
            long b0 = System.nanoTime();
            api.iplPathBakerBake(context.getValue(), params, progress, null);
            say("192 x 192 m, 4 m spacing, %d probes, %d threads: %.0f ms", positions.size(), threads, (System.nanoTime() - b0) / 1e6);
            if (threads == 4) {
                // Cancelling a path bake crashes phonon.dll 4.8.1 (see the cancel* tests); not done here.
                // Alternate paths on the large graph after the gap is walled up.
                var sealed = new ArrayList<>(big);
                sealed.add(new double[] {-0.5, -6, 88, 0.5, 6, 96, 0});
                Pointer sealedScene = embreeScene(context.getValue(), embree.getValue(), mesh(sealed), keep);
                try (PathRuntime runtime = new PathRuntime(context.getValue(), sealedScene, 1, batch)) {
                    Vec3 src = new Vec3(-20, -4.5, 80), listener = new Vec3(20, -4.5, 70);
                    for (boolean[] step : new boolean[][] {{false, false}, {true, false}, {true, true}, {true, true}, {true, true}}) {
                        long r0 = System.nanoTime();
                        var out = runtime.run(batch, src, listener, step[0], step[1]);
                        say("  sealed big hall: validate=%s alternate=%s %.1f ms %s", step[0], step[1], (System.nanoTime() - r0) / 1e6, describe(out));
                    }
                }
            }
            api.iplProbeBatchRelease(new PointerByReference(batch));
        }
        log.close();
    }

    @Test void bakeNullCallbackOneThread() throws Exception { cancelStep(1, false, false); }
    @Test void bakeNullCallbackFourThreads() throws Exception { cancelStep(4, false, false); }
    @Test void bakeCallbackOneThreadOnWorker() throws Exception { cancelStep(1, true, false); }
    // iplPathBakerCancelBake returns, then the bake thread faults and the JVM dies (phonon.dll 4.8.1).
    @Disabled("Kills the JVM: path bake cancel crashes phonon.dll 4.8.1")
    @Test void cancelCallbackOneThread() throws Exception { cancelStep(1, true, true); }
    @Disabled("Kills the JVM: path bake cancel crashes phonon.dll 4.8.1")
    @Test void cancelCallbackFourThreads() throws Exception { cancelStep(4, true, true); }

    private static void cancelStep(int threads, boolean callback, boolean cancel) throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        SteamGpu.api();
        api = Native.load(SteamAudio.library().toString(), PathApi.class);
        log = new PrintWriter(Files.newBufferedWriter(Path.of("pathing-cancel.txt"), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND));
        var context = new PointerByReference();
        SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "context");
        var embree = new PointerByReference();
        SteamAudio.check(api.iplEmbreeDeviceCreate(context.getValue(), null, embree), "embree");
        List<Object> keep = new ArrayList<>();
        Pointer bigScene = embreeScene(context.getValue(), embree.getValue(), mesh(partitionedHall(96, 96, 1)), keep);
        List<Vec3> positions = new ArrayList<>();
        Pointer batch = floorProbes(context.getValue(), bigScene, 4, 192, 192, positions);
        var params = new PathBakeParams();
        params.scene = bigScene;
        params.probeBatch = batch;
        params.identifier.type = 1;
        params.identifier.variation = 3;
        params.numSamples = 4;
        params.radius = 1;
        params.threshold = 0.1f;
        params.visRange = 50;
        params.pathRange = 100;
        params.numThreads = threads;
        float[] last = {0};
        int[] calls = {0};
        SteamBakeFeasibilityScratchTest.Progress progress = (value, user) -> { last[0] = value; calls[0]++; };
        say("threads %d, callback %s, cancel %s:", threads, callback, cancel);
        Thread worker = new Thread(() -> api.iplPathBakerBake(context.getValue(), params, callback ? progress : null, null));
        long t0 = System.nanoTime();
        worker.start();
        if (cancel) {
            Thread.sleep(300);
            say("  cancelling at progress %.3f (%d callbacks)", last[0], calls[0]);
            long c0 = System.nanoTime();
            api.iplPathBakerCancelBake(context.getValue());
            say("  cancel call returned");
            worker.join();
            say("  bake returned %.1f ms after cancel", (System.nanoTime() - c0) / 1e6);
        } else {
            worker.join();
        }
        say("  bake %.0f ms, progress %.3f, %d callbacks, data %d bytes", (System.nanoTime() - t0) / 1e6, last[0], calls[0],
                api.iplProbeBatchGetDataSize(batch, pathingId()));
        java.lang.ref.Reference.reachabilityFence(progress);
        log.close();
    }

    private static SteamAudio.BakedId pathingId() {
        var id = new SteamAudio.BakedId();
        id.type = 1;
        id.variation = 3;
        return id;
    }

    private static void bakePathing(Pointer context, Pointer scene, Pointer batch, float visRange, float pathRange) {
        var params = new PathBakeParams();
        params.scene = scene;
        params.probeBatch = batch;
        params.identifier.type = 1;
        params.identifier.variation = 3;
        params.numSamples = 4;
        params.radius = 1;
        params.threshold = 0.1f;
        params.visRange = visRange;
        params.pathRange = pathRange;
        params.numThreads = 8;
        SteamBakeFeasibilityScratchTest.Progress progress = (value, user) -> { };
        api.iplPathBakerBake(context, params, progress, null);
        java.lang.ref.Reference.reachabilityFence(progress);
    }

    /** RMS gain per Ambisonic channel of the path effect on white noise, after the effect settles. */
    private static double[] render(Pointer effect, SteamAudio.SimulationOutputs out) {
        var in = new SteamAudio.AudioBuffer();
        var ambi = new SteamAudio.AudioBuffer(4);
        float[] noise = new float[SteamRenderer.FRAME];
        java.util.Random random = new java.util.Random(1);
        double inEnergy = 0;
        double[] outEnergy = new double[4];
        api.iplPathEffectReset(effect);
        out.pathing.order = 1;
        for (int block = 0; block < 40; block++) {
            for (int i = 0; i < noise.length; i++) noise[i] = (float) random.nextGaussian() * 0.1f;
            in.memory(0).write(0, noise, 0, noise.length);
            api.iplPathEffectApply(effect, out.pathing, in, ambi);
            if (block < 10) continue;
            for (float v : noise) inEnergy += v * (double) v;
            for (int c = 0; c < 4; c++) for (float v : ambi.memory(c).getFloatArray(0, SteamRenderer.FRAME)) outEnergy[c] += v * (double) v;
        }
        double[] gain = new double[4];
        for (int c = 0; c < 4; c++) gain[c] = Math.sqrt(outEnergy[c] / inEnergy);
        return gain;
    }

    private static Pointer embreeScene(Pointer context, Pointer embree, AcousticMesh.Data data, List<Object> keep) {
        var settings = new SteamAudio.SceneSettings();
        settings.type = 1;
        settings.embree = embree;
        var scene = new PointerByReference();
        SteamAudio.check(api.iplSceneCreate(context, settings, scene), "scene");
        var vertexData = new com.sun.jna.Memory(data.vertices().length * 4L);
        var triangleData = new com.sun.jna.Memory(data.triangles().length * 4L);
        var materialIndices = new com.sun.jna.Memory(data.materials().length * 4L);
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
        var meshRef = new PointerByReference();
        SteamAudio.check(api.iplStaticMeshCreate(scene.getValue(), mesh, meshRef), "mesh");
        api.iplStaticMeshAdd(meshRef.getValue(), scene.getValue());
        api.iplSceneCommit(scene.getValue());
        keep.addAll(List.of(settings, vertexData, triangleData, materialIndices, materials, mesh, meshRef));
        return scene.getValue();
    }

    private static Pointer customScene(Pointer context, List<double[]> boxes, List<Object> keep) {
        var material = new SteamAudio.Material();
        material.absorption = AcousticMaterials.absorption(AcousticMaterials.STONE);
        material.transmission = AcousticMaterials.transmission(AcousticMaterials.STONE, 1);
        material.write();
        long materialOffset = ((28L + Native.POINTER_SIZE - 1) / Native.POINTER_SIZE) * Native.POINTER_SIZE;
        SteamAudio.ClosestHit closest = (ray, min, max, hit, user) -> {
            hit.setFloat(0, Float.POSITIVE_INFINITY);
            hit.setPointer(materialOffset, null);
            float[] r = ray.getFloatArray(0, 6);
            Vec3 o = new Vec3(r[0], r[1], r[2]), d = new Vec3(r[3], r[4], r[5]).normalize();
            AcousticRay result = cast(boxes, o.add(d.scale(Math.max(min, 0.002))), o.add(d.scale(Math.min(max, 192))));
            if (result.kind() == AcousticRay.Kind.HIT) {
                hit.write(4, new int[] {0, 0, 0}, 0, 3);
                hit.write(16, new float[] {(float) result.normal().x, (float) result.normal().y, (float) result.normal().z}, 0, 3);
                hit.setPointer(materialOffset, material.getPointer());
                hit.setFloat(0, (float) o.distanceTo(result.position()));
            }
        };
        SteamAudio.AnyHit any = (ray, min, max, occluded, user) -> {
            float[] r = ray.getFloatArray(0, 6);
            Vec3 o = new Vec3(r[0], r[1], r[2]), d = new Vec3(r[3], r[4], r[5]).normalize();
            occluded.setByte(0, (byte) (max > min && cast(boxes, o.add(d.scale(Math.max(min, 0.002))), o.add(d.scale(Math.min(max, 192))))
                    .kind() != AcousticRay.Kind.MISS ? 1 : 0));
        };
        var settings = new SteamAudio.SceneSettings();
        settings.closest = closest;
        settings.any = any;
        var scene = new PointerByReference();
        SteamAudio.check(api.iplSceneCreate(context, settings, scene), "custom scene");
        api.iplSceneCommit(scene.getValue());
        keep.addAll(List.of(material, closest, any, settings));
        return scene.getValue();
    }

    private static float generatedRadius;

    private static Pointer floorProbes(Pointer context, Pointer scene, float spacing, float sizeX, float sizeZ, List<Vec3> positions) {
        return floorProbes(context, scene, spacing, sizeX, sizeZ, positions, List.of());
    }

    private static Pointer floorProbes(Pointer context, Pointer scene, float spacing, float sizeX, float sizeZ, List<Vec3> positions,
            List<Vec3> extra) {
        var array = new PointerByReference();
        SteamAudio.check(api.iplProbeArrayCreate(context, array), "probe array");
        var params = new SteamBakeFeasibilityScratchTest.GenerationParams();
        params.spacing = spacing;
        params.height = 1.5f;
        params.transform[0] = sizeX - 0.2f;
        params.transform[5] = 11.8f;
        params.transform[10] = sizeZ - 0.2f;
        // Steam's generator volume is the unit cube centred on the origin: translation is the centre.
        params.transform[3] = 0;
        params.transform[7] = 0;
        params.transform[11] = 0;
        params.transform[15] = 1;
        api.iplProbeArrayGenerateProbes(array.getValue(), scene, params);
        int count = api.iplProbeArrayGetNumProbes(array.getValue());
        var batch = new PointerByReference();
        SteamAudio.check(api.iplProbeBatchCreate(context, batch), "probe batch");
        for (int i = 0; i < count; i++) {
            var probe = api.iplProbeArrayGetProbe(array.getValue(), i);
            positions.add(new Vec3(probe.center.x, probe.center.y, probe.center.z));
            generatedRadius = probe.radius;
            api.iplProbeBatchAddProbe(batch.getValue(), probe);
        }
        for (Vec3 v : extra) {
            var probe = new SteamBakeFeasibilityScratchTest.SphereValue();
            probe.center.set(v.x, v.y, v.z);
            probe.radius = generatedRadius;
            positions.add(v);
            api.iplProbeBatchAddProbe(batch.getValue(), probe);
        }
        api.iplProbeBatchCommit(batch.getValue());
        api.iplProbeArrayRelease(array);
        return batch.getValue();
    }
}
