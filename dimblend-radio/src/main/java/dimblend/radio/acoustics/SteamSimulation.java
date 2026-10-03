package dimblend.radio.acoustics;

import com.sun.jna.Pointer;
import com.sun.jna.Native;
import com.sun.jna.Memory;
import com.sun.jna.ptr.PointerByReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.world.phys.Vec3;

/** Steam Audio simulation using immutable CPU rays or a GPU triangle scene. */
public final class SteamSimulation implements AutoCloseable {
    // Steam Audio 4.8.1 gatherEnergyField launches max(256, numRays) work items,
    // using that launch size as the SH stride, without a rayIndex bounds check.
    // Allocate AND trace whole workgroups (a multiple of 256); other counts read
    // uninitialized/OOB data. One workgroup left the field too sparse to converge.
    public static final int GPU_RAYS = 1024;
    /** {@code IPLSimulationFlags}. */
    public static final int DIRECT = 1, REFLECTIONS = 2, PATHING = 4;
    /** Steam Audio's diffracted path, before {@link AcousticPathing#shape}: band gains and world-space SH (W, Y, Z, X). */
    public record RawPath(float[] eq, float[] sh) { }
    /** A hit's material and its run's transmission per band, in {@link #TRANSMISSION_STEP_DB} steps. */
    private record MaterialKey(int material, int low, int mid, int high) { }
    /** Inaudible (about 1% amplitude) and keeps the native material records few and reusable. */
    private static final double TRANSMISSION_STEP_DB = 0.1;
    /** Mixed walls can yield many transmission combinations; drop the records between runs past this. */
    private static final int MAX_CACHED_MATERIALS = 4096;
    private final SteamAudio.Api api = SteamAudio.api();
    private final PointerByReference context = new PointerByReference();
    private final PointerByReference scene = new PointerByReference();
    private final PointerByReference simulator = new PointerByReference();
    private final PointerByReference source = new PointerByReference();
    /** Sources added after construction (see {@link #addSource}); the constructor's own is not among them. */
    private final List<Source> added = new ArrayList<>();
    private final SteamAudio.SceneSettings sceneSettings = new SteamAudio.SceneSettings();
    private final Map<MaterialKey, SteamAudio.Material> materials = new HashMap<>();
    private final int flags;
    /** Most sources one reflection run traces: Radeon Rays ignores the ones past it. */
    private final int sourcesPerRun;
    private final boolean gpu;
    private final PointerByReference openCL = new PointerByReference(), radeon = new PointerByReference();
    private SteamStaticMesh mesh;
    /** The parts the uploaded scene mesh was combined from. */
    private AcousticMesh.Data uploadedTerrain, uploadedStructures;
    /** Scene meshes uploaded so far. */
    private long uploads;
    private BiFunction<Vec3, Vec3, AcousticRay> tracer;
    private Vec3 offset = Vec3.ZERO;
    private Throwable callbackFailure;
    /** The attached pathing bake (see {@link #attachPathing}); its probes are relative to its frame origin. */
    private final PointerByReference pathingBatch = new PointerByReference();
    /** Uniform theory of diffraction; passed by pointer, so kept for the simulator's lifetime. */
    private final SteamAudio.DeviationModel deviation = new SteamAudio.DeviationModel();

    public SteamSimulation(int rate, int flags) {
        this(rate, flags, false);
    }

    public SteamSimulation(int rate, int flags, boolean gpu) {
        this(rate, flags, gpu, 1);
    }

    /**
     * @param sourcesPerRun most {@link #addSource added} sources one reflection run traces together
     *     (Steam Audio's {@code maxNumSources}); runs for more are split
     */
    public SteamSimulation(int rate, int flags, boolean gpu, int sourcesPerRun) {
        if (sourcesPerRun < 1) throw new IllegalArgumentException("A run traces at least one source");
        this.flags = flags;
        this.sourcesPerRun = sourcesPerRun;
        this.gpu = gpu;
        try {
            SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "context");
            if (gpu) {
                var gpuApi = SteamGpu.api();
                var list = new PointerByReference();
                SteamAudio.check(gpuApi.iplOpenCLDeviceListCreate(context.getValue(), new SteamGpu.DeviceSettings(), list), "GPU enumeration");
                try {
                    if (gpuApi.iplOpenCLDeviceListGetNumDevices(list.getValue()) == 0) throw new IllegalStateException("No OpenCL GPU");
                    SteamAudio.check(gpuApi.iplOpenCLDeviceCreate(context.getValue(), list.getValue(), 0, openCL), "GPU device");
                    SteamAudio.check(gpuApi.iplRadeonRaysDeviceCreate(openCL.getValue(), null, radeon), "GPU ray tracer");
                } finally { gpuApi.iplOpenCLDeviceListRelease(list); }
                sceneSettings.type = 2;
                sceneSettings.radeon = radeon.getValue();
            } else {
                sceneSettings.closest = this::closest;
                sceneSettings.any = this::any;
            }
            SteamAudio.check(api.iplSceneCreate(context.getValue(), sceneSettings, scene), "scene");
            api.iplSceneCommit(scene.getValue());
            var settings = new SteamAudio.SimulationSettings();
            settings.flags = flags;
            settings.samplingRate = rate;
            settings.order = 1;
            settings.sources = sourcesPerRun;
            // Volumetric occlusion (AcousticDiffraction) on the CPU direct path.
            if ((flags & 1) != 0 && !gpu) settings.occlusionSamples = AcousticDiffraction.MAX_SAMPLES;
            if ((flags & PATHING) != 0) {
                if (gpu) throw new IllegalArgumentException("Pathing runs on the CPU scene");
                settings.visSamples = AcousticPathing.VISIBILITY_SAMPLES;
                deviation.write();
            }
            if (gpu) {
                settings.maxRays = GPU_RAYS;
                settings.sceneType = 2;
                settings.openCL = openCL.getValue();
                settings.radeon = radeon.getValue();
            }
            SteamAudio.check(api.iplSimulatorCreate(context.getValue(), settings, simulator), "simulator");
            api.iplSimulatorSetScene(simulator.getValue(), scene.getValue());
            var sourceSettings = new SteamAudio.SourceSettings();
            sourceSettings.flags = flags;
            SteamAudio.check(api.iplSourceCreate(simulator.getValue(), sourceSettings, source), "source");
            api.iplSourceAdd(source.getValue(), simulator.getValue());
            api.iplSimulatorCommit(simulator.getValue());
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    public Pointer context() { return context.getValue(); }

    /**
     * Another sound source in this simulator, for {@link #simulateGpu(AcousticMesh.Data,
     * AcousticMesh.Data, Vec3, List, List, int)}: sources share the scene and the listener's rays,
     * and each keeps its own outputs (its reflection IR). Close it on the thread running this simulation.
     */
    public final class Source implements AutoCloseable {
        private final PointerByReference handle = new PointerByReference();

        private Source() {
            var settings = new SteamAudio.SourceSettings();
            settings.flags = flags;
            SteamAudio.check(api.iplSourceCreate(simulator.getValue(), settings, handle), "source");
            api.iplSourceAdd(handle.getValue(), simulator.getValue());
            api.iplSimulatorCommit(simulator.getValue());
        }

        /** Removes the source; its outputs (and the IR they point to) must no longer be in use. */
        @Override public void close() {
            if (handle.getValue() == null) return;
            added.remove(this);
            api.iplSourceRemove(handle.getValue(), simulator.getValue());
            api.iplSimulatorCommit(simulator.getValue());
            api.iplSourceRelease(handle);
            handle.setValue(null);
        }

        public boolean closed() { return handle.getValue() == null; }
    }

    public Source addSource() {
        if (simulator.getValue() == null) throw new IllegalStateException("Simulation closed");
        Source added = new Source();
        this.added.add(added);
        return added;
    }

    /** Sources added with {@link #addSource} and not closed yet. */
    public int sourceCount() { return added.size(); }

    public boolean gpu() { return gpu; }

    /** How many times a changed scene mesh was uploaded; by the thread that runs simulations. */
    public long uploads() { return uploads; }

    private static boolean sameGeometry(AcousticMesh.Data a, AcousticMesh.Data b) {
        return a == b || a != null && b != null && a.origin().equals(b.origin())
                && java.util.Arrays.equals(a.vertices(), b.vertices())
                && java.util.Arrays.equals(a.triangles(), b.triangles())
                && java.util.Arrays.equals(a.materials(), b.materials());
    }

    /**
     * Simulates against the given geometry, re-uploading the scene mesh in place when it changed
     * (iplStaticMeshRemove + create + add + commit). Engines are long-lived: per-run engine
     * replacement was found to corrupt the native chain (NaN wet field after the second
     * close-retain cycle) and stall the worker on teardown.
     */
    public SteamAudio.SimulationOutputs simulateGpu(AcousticMesh.Data data, Vec3 listenerWorld,
            Vec3 sourceWorld, int rays, int bounces) {
        return simulateGpu(data, null, listenerWorld, sourceWorld, rays, bounces);
    }

    /**
     * As {@link #simulateGpu(AcousticMesh.Data, Vec3, Vec3, int, int)} for terrain and structures
     * kept apart by the caller; the scene is re-uploaded only when either part changed.
     * <p>
     * Both go up as ONE static mesh. Steam Audio 4.8.1 traces every mesh of a Radeon Rays scene but
     * shades each hit with the first mesh's normal, material-index and material buffers
     * ({@code radeonrays_reflection_simulator.cpp}: {@code scene.staticMeshes().front()}): hits on a
     * second mesh get the first one's surfaces, and read past its buffers when the second has more
     * triangles, which faults {@code iplSimulatorRunReflections} ("Invalid memory access").
     *
     * @param structures may be null; otherwise it must share {@code terrain}'s origin
     */
    public SteamAudio.SimulationOutputs simulateGpu(AcousticMesh.Data terrain, AcousticMesh.Data structures,
            Vec3 listenerWorld, Vec3 sourceWorld, int rays, int bounces) {
        if (!gpu) throw new IllegalStateException("Not a GPU simulator");
        if (structures != null && !structures.origin().equals(terrain.origin())) {
            throw new IllegalArgumentException("Scene meshes must share one origin");
        }
        if (!sameGeometry(uploadedTerrain, terrain) || !sameGeometry(uploadedStructures, structures)) {
            upload(AcousticMesh.Data.concat(terrain, structures));
        }
        uploadedTerrain = terrain;
        uploadedStructures = structures;
        return run(listenerWorld, sourceWorld, rays, bounces);
    }

    /**
     * Reflections of several {@link #addSource added} sources in one run: the scene is uploaded
     * (when it changed) and the listener's rays are traced once, each source gathering its own
     * response. More sources than the simulator traces at once are run in batches of that many.
     * Sources of this simulator not listed sit the run out and keep their last outputs; Radeon Rays
     * scenes never accumulate across runs, so that leaves nothing stale behind.
     *
     * @param positions world position of each of {@code sources}, in order
     * @return the outputs of each of {@code sources}, in order
     */
    public List<SteamAudio.SimulationOutputs> simulateGpu(AcousticMesh.Data terrain, AcousticMesh.Data structures,
            Vec3 listenerWorld, List<Source> sources, List<Vec3> positions, int bounces) {
        if (!gpu || flags != REFLECTIONS) throw new IllegalStateException("Not a GPU reflection simulator");
        if (sources.size() != positions.size()) throw new IllegalArgumentException("One position per source");
        if (structures != null && !structures.origin().equals(terrain.origin())) {
            throw new IllegalArgumentException("Scene meshes must share one origin");
        }
        for (Source listed : sources) {
            if (listed.closed() || !added.contains(listed)) throw new IllegalArgumentException("Not a source of this simulation");
            if (sources.indexOf(listed) != sources.lastIndexOf(listed)) throw new IllegalArgumentException("Source listed twice");
        }
        if (!sameGeometry(uploadedTerrain, terrain) || !sameGeometry(uploadedStructures, structures)) {
            upload(AcousticMesh.Data.concat(terrain, structures));
        }
        uploadedTerrain = terrain;
        uploadedStructures = structures;
        sitOut(source.getValue());
        var shared = new SteamAudio.SharedInputs();
        shared.rays = GPU_RAYS;
        shared.bounces = bounces;
        shared.order = 1;
        Vec3 listener = listenerWorld.subtract(offset);
        shared.listener.origin = new SteamAudio.Vector(listener.x, listener.y, listener.z);
        api.iplSimulatorSetSharedInputs(simulator.getValue(), REFLECTIONS, shared);
        List<SteamAudio.SimulationOutputs> results = new ArrayList<>(sources.size());
        for (int from = 0; from < sources.size(); from += sourcesPerRun) {
            List<Source> batch = sources.subList(from, Math.min(sources.size(), from + sourcesPerRun));
            for (Source other : added) {
                int index = batch.indexOf(other);
                if (index < 0) {
                    sitOut(other.handle.getValue());
                    continue;
                }
                var inputs = new SteamAudio.SimulationInputs();
                inputs.flags = REFLECTIONS;
                Vec3 relative = positions.get(from + index).subtract(offset);
                inputs.source.origin = new SteamAudio.Vector(relative.x, relative.y, relative.z);
                api.iplSourceSetInputs(other.handle.getValue(), REFLECTIONS, inputs);
            }
            // Radeon Rays cannot trace an empty acceleration structure. Open air has no wet response.
            if (mesh != null) api.iplSimulatorRunReflections(simulator.getValue());
            for (Source listed : batch) {
                var outputs = new SteamAudio.SimulationOutputs();
                api.iplSourceGetOutputs(listed.handle.getValue(), REFLECTIONS, outputs);
                if (mesh == null) outputs.reflections.ir = null;
                results.add(outputs);
            }
        }
        return results;
    }

    /** {@code handle} takes no part in the next reflection run. */
    private void sitOut(Pointer handle) {
        var inputs = new SteamAudio.SimulationInputs();
        inputs.flags = 0;
        api.iplSourceSetInputs(handle, REFLECTIONS, inputs);
    }

    private void upload(AcousticMesh.Data data) {
        uploads++;
        if (mesh != null) {
            mesh.close();
            mesh = null;
        }
        offset = data.origin();
        if (data.triangleCount() > 0) mesh = new SteamStaticMesh(scene.getValue(), data, AcousticMaterials.GPU_SCATTERING);
        api.iplSceneCommit(scene.getValue());
        api.iplSimulatorCommit(simulator.getValue());
    }

    public SteamAudio.SimulationOutputs simulate(BiFunction<Vec3, Vec3, AcousticRay> tracer,
            Vec3 listenerWorld, Vec3 sourceWorld, int rays, int bounces) {
        this.tracer = tracer;
        offset = listenerWorld;
        // The solver holds hit material pointers only during a run.
        if (materials.size() > MAX_CACHED_MATERIALS) materials.clear();
        return run(listenerWorld, sourceWorld, rays, bounces);
    }

    /**
     * Swaps in a baked pathing probe batch (the bytes {@code iplProbeBatchSave} wrote, in any
     * context), or detaches the current one when {@code serialized} is null.
     */
    public void attachPathing(byte[] serialized) {
        if ((flags & PATHING) == 0) throw new IllegalStateException("Not a pathing simulator");
        if (pathingBatch.getValue() != null) {
            api.iplSimulatorRemoveProbeBatch(simulator.getValue(), pathingBatch.getValue());
            api.iplSimulatorCommit(simulator.getValue());
            api.iplProbeBatchRelease(pathingBatch);
            pathingBatch.setValue(null);
        }
        if (serialized == null) return;
        var data = new Memory(Math.max(1, serialized.length));
        data.write(0, serialized, 0, serialized.length);
        var settings = new SteamAudio.SerializedObjectSettings();
        settings.data = data;
        settings.size = serialized.length;
        var object = new PointerByReference();
        SteamAudio.check(api.iplSerializedObjectCreate(context.getValue(), settings, object), "serialized pathing");
        try {
            SteamAudio.check(api.iplProbeBatchLoad(context.getValue(), object.getValue(), pathingBatch), "pathing probes");
        } finally {
            api.iplSerializedObjectRelease(object);
            java.lang.ref.Reference.reachabilityFence(data);
        }
        api.iplProbeBatchCommit(pathingBatch.getValue());
        api.iplSimulatorAddProbeBatch(simulator.getValue(), pathingBatch.getValue());
        api.iplSimulatorCommit(simulator.getValue());
    }

    public boolean hasPathing() { return pathingBatch.getValue() != null; }

    /**
     * Finds the diffracted path through the attached probes. The voxel scene is traced in the
     * bake's frame (its probes are stored relative to {@code frameOrigin}).
     *
     * @param validate also re-trace the baked route and look for alternatives where it is blocked
     *     now: for a bake older than the last edit near it
     */
    public RawPath runPathing(BiFunction<Vec3, Vec3, AcousticRay> tracer, Vec3 frameOrigin,
            Vec3 listenerWorld, Vec3 sourceWorld, boolean validate) {
        if (pathingBatch.getValue() == null) throw new IllegalStateException("No pathing attached");
        this.tracer = tracer;
        offset = frameOrigin;
        callbackFailure = null;
        if (materials.size() > MAX_CACHED_MATERIALS) materials.clear();
        var inputs = new SteamAudio.SimulationInputs();
        inputs.flags = PATHING;
        Vec3 sourceLocal = sourceWorld.subtract(frameOrigin);
        inputs.source.origin.set(sourceLocal.x, sourceLocal.y, sourceLocal.z);
        inputs.probes = pathingBatch.getValue();
        inputs.visRadius = AcousticPathing.SAMPLE_RADIUS;
        inputs.visThreshold = AcousticPathing.VISIBILITY_THRESHOLD;
        inputs.visRange = AcousticPathing.VISIBILITY_RANGE;
        inputs.pathOrder = 1;
        inputs.validation = validate ? 1 : 0;
        inputs.alternate = validate ? 1 : 0;
        inputs.deviation = deviation.getPointer();
        api.iplSourceSetInputs(source.getValue(), PATHING, inputs);
        var shared = new SteamAudio.SharedInputs();
        Vec3 listenerLocal = listenerWorld.subtract(frameOrigin);
        shared.listener.origin.set(listenerLocal.x, listenerLocal.y, listenerLocal.z);
        api.iplSimulatorSetSharedInputs(simulator.getValue(), PATHING, shared);
        api.iplSimulatorRunPathing(simulator.getValue());
        if (callbackFailure != null) throw new IllegalStateException("Acoustic geometry callback failed", callbackFailure);
        var outputs = new SteamAudio.SimulationOutputs();
        api.iplSourceGetOutputs(source.getValue(), PATHING, outputs);
        // Simulator memory: the next run overwrites it.
        Pointer coefficients = outputs.pathing.coefficients;
        float[] sh = coefficients == null ? new float[4] : coefficients.getFloatArray(0, 4);
        return new RawPath(outputs.pathing.eq.clone(), sh);
    }

    private SteamAudio.SimulationOutputs run(Vec3 listenerWorld, Vec3 sourceWorld, int rays, int bounces) {
        callbackFailure = null;
        // Pathing runs on its own (runPathing): it has its own frame and cadence.
        int active = flags & (DIRECT | REFLECTIONS);
        var inputs = new SteamAudio.SimulationInputs();
        inputs.flags = active;
        Vec3 relative = sourceWorld.subtract(offset);
        inputs.source.origin = new SteamAudio.Vector(relative.x, relative.y, relative.z);
        boolean volumetric = (flags & 1) != 0 && occludeVolumetrically(inputs);
        // The voxel scene traces its own transmission (AcousticDirectTransmission).
        boolean traced = (flags & 1) != 0 && !gpu && tracer != null;
        if (traced) inputs.directFlags &= ~16;
        api.iplSourceSetInputs(source.getValue(), active, inputs);
        if ((active & REFLECTIONS) != 0) for (Source other : added) sitOut(other.handle.getValue());
        var shared = new SteamAudio.SharedInputs();
        shared.rays = gpu ? GPU_RAYS : rays;
        shared.bounces = bounces;
        shared.order = 1;
        Vec3 listener = listenerWorld.subtract(offset);
        shared.listener.origin = new SteamAudio.Vector(listener.x, listener.y, listener.z);
        api.iplSimulatorSetSharedInputs(simulator.getValue(), active, shared);
        if ((flags & 1) != 0) api.iplSimulatorRunDirect(simulator.getValue());
        // Radeon Rays cannot trace an empty acceleration structure. Open air has no wet response.
        boolean reflect = (flags & 2) != 0 && (!gpu || mesh != null);
        if (reflect) api.iplSimulatorRunReflections(simulator.getValue());
        if (callbackFailure != null) throw new IllegalStateException("Acoustic geometry callback failed", callbackFailure);
        var outputs = new SteamAudio.SimulationOutputs();
        api.iplSourceGetOutputs(source.getValue(), active, outputs);
        if (!reflect) outputs.reflections.ir = null;
        if (traced) {
            float[] transmission = AcousticDirectTransmission.between(listenerWorld, sourceWorld, tracer);
            if (volumetric && outputs.direct.occlusion < 1) {
                outputs.direct.occlusion = Math.max(outputs.direct.occlusion, listenerSideOcclusion(inputs, listener, relative));
                transmission = AcousticDiffraction.hiddenTransmission(transmission);
            }
            outputs.direct.transmission = transmission;
        }
        outputs.direct.flags = 27;
        outputs.direct.transmissionType = 1;
        return outputs;
    }

    /**
     * Picks the direct path's occlusion mode: with {@link AcousticDiffraction} on, a sphere around
     * the source is sampled even when the centre line is clear, so the level stays continuous
     * across the shadow boundary (about half the sphere is visible on either side of it). CPU
     * scenes only: a Radeon Rays scene has no single-ray queries.
     *
     * @return whether the volumetric mode was chosen
     */
    private boolean occludeVolumetrically(SteamAudio.SimulationInputs inputs) {
        float radius = AcousticDiffraction.radius();
        if (gpu || tracer == null || radius <= 0) return false;
        inputs.occlusionType = 1;
        inputs.occlusionRadius = radius;
        inputs.occlusionSamples = Math.min(AcousticDiffraction.MAX_SAMPLES, AcousticDiffraction.samples());
        return true;
    }

    /**
     * The same volumetric occlusion with the sphere around the listener (source and listener
     * swapped), so an opening near the listener counts as well as one near the source. Only run
     * when the source sphere is partly hidden; the larger share wins, which keeps it continuous.
     * It must run whether or not the centre line is clear: an obstacle next to the listener hides
     * nearly all of the source sphere (its rays converge there) while the line is still clear, and
     * the listener sphere is what keeps that share from collapsing and then jumping back.
     */
    private float listenerSideOcclusion(SteamAudio.SimulationInputs inputs, Vec3 listener, Vec3 sourcePosition) {
        inputs.source.origin = new SteamAudio.Vector(listener.x, listener.y, listener.z);
        inputs.directFlags = 8;
        api.iplSourceSetInputs(source.getValue(), 1, inputs);
        var shared = new SteamAudio.SharedInputs();
        shared.listener.origin = new SteamAudio.Vector(sourcePosition.x, sourcePosition.y, sourcePosition.z);
        api.iplSimulatorSetSharedInputs(simulator.getValue(), 1, shared);
        api.iplSimulatorRunDirect(simulator.getValue());
        if (callbackFailure != null) throw new IllegalStateException("Acoustic geometry callback failed", callbackFailure);
        var swapped = new SteamAudio.SimulationOutputs();
        api.iplSourceGetOutputs(source.getValue(), 1, swapped);
        return swapped.direct.occlusion;
    }

    private AcousticRay cast(float[] ray, float min, float max) {
        Vec3 origin = offset.add(ray[0], ray[1], ray[2]);
        Vec3 direction = new Vec3(ray[3], ray[4], ray[5]).normalize();
        double start = Math.max(0.002, min);
        double end = Math.min(192, max);
        if (end <= start || tracer == null) return AcousticRay.miss(origin);
        return tracer.apply(origin.add(direction.scale(start)), origin.add(direction.scale(end)));
    }

    private void closest(Pointer rayPointer, float min, float max, Pointer hitPointer, Pointer user) {
        long materialOffset = ((28L + Native.POINTER_SIZE - 1) / Native.POINTER_SIZE) * Native.POINTER_SIZE;
        hitPointer.setFloat(0, Float.POSITIVE_INFINITY);
        hitPointer.setPointer(materialOffset, null);
        try {
            float[] ray = rayPointer.getFloatArray(0, 6);
            AcousticRay result = cast(ray, min, max);
            if (result.kind() == AcousticRay.Kind.HIT) {
                Vec3 origin = offset.add(ray[0], ray[1], ray[2]);
                float distance = (float) origin.distanceTo(result.position());
                Pointer surface = material(result.material(), result.transmission()).getPointer();
                hitPointer.write(4, new int[] {0, 0, 0}, 0, 3);
                hitPointer.write(16, new float[] {(float) result.normal().x, (float) result.normal().y,
                        (float) result.normal().z}, 0, 3);
                hitPointer.setPointer(materialOffset, surface);
                // Last: a finite distance tells the solver every other field is valid.
                hitPointer.setFloat(0, distance);
            }
        } catch (Throwable error) {
            callbackFailure = error;
            hitPointer.setFloat(0, Float.POSITIVE_INFINITY);
        }
    }

    private void any(Pointer ray, float min, float max, Pointer output, Pointer user) {
        // An empty segment is clear: volumetric occlusion's first sample is the sphere's centre,
        // and Steam Audio asks whether the centre occludes itself.
        try { output.setByte(0, (byte) (max > min && cast(ray.getFloatArray(0, 6), min, max).kind() != AcousticRay.Kind.MISS ? 1 : 0)); }
        catch (Throwable error) { callbackFailure = error; output.setByte(0, (byte) 1); }
    }

    /**
     * Absorption follows the entered block's material; transmission is the whole run's (the
     * solver multiplies one value per hit), quantized so equal walls share one native record.
     */
    private SteamAudio.Material material(int material, float[] transmission) {
        var key = new MaterialKey(material, transmissionStep(transmission[0]), transmissionStep(transmission[1]),
                transmissionStep(transmission[2]));
        return materials.computeIfAbsent(key, value -> {
            var entry = new SteamAudio.Material();
            entry.absorption = AcousticMaterials.absorption(value.material());
            // The SDK seeds diffuse scattering from wall-clock time. Voxel surface normals
            // already provide geometric scattering; keep CPU material paths repeatable.
            entry.scattering = 0;
            entry.transmission = new float[] {transmissionOf(value.low()), transmissionOf(value.mid()),
                    transmissionOf(value.high())};
            entry.write();
            return entry;
        });
    }

    private static int transmissionStep(float transmission) {
        return (int) Math.round(-20 * Math.log10(Math.max(1e-6f, transmission)) / TRANSMISSION_STEP_DB);
    }

    private static float transmissionOf(int step) {
        return (float) Math.pow(10, -step * TRANSMISSION_STEP_DB / 20);
    }

    @Override public void close() {
        if (simulator.getValue() != null) for (Source other : List.copyOf(added)) other.close();
        if (source.getValue() != null) {
            api.iplSourceRemove(source.getValue(), simulator.getValue());
            api.iplSourceRelease(source);
        }
        if (pathingBatch.getValue() != null) {
            if (simulator.getValue() != null) api.iplSimulatorRemoveProbeBatch(simulator.getValue(), pathingBatch.getValue());
            api.iplProbeBatchRelease(pathingBatch);
        }
        if (simulator.getValue() != null) api.iplSimulatorRelease(simulator);
        if (mesh != null) mesh.close();
        if (scene.getValue() != null) api.iplSceneRelease(scene);
        if (radeon.getValue() != null) SteamGpu.api().iplRadeonRaysDeviceRelease(radeon);
        if (openCL.getValue() != null) SteamGpu.api().iplOpenCLDeviceRelease(openCL);
        if (context.getValue() != null) api.iplContextRelease(context);
    }
}
