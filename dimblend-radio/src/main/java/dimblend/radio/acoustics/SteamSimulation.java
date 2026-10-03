package dimblend.radio.acoustics;

import com.sun.jna.Pointer;
import com.sun.jna.Native;
import com.sun.jna.Memory;
import com.sun.jna.ptr.PointerByReference;
import java.util.HashMap;
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
    private final SteamAudio.SceneSettings sceneSettings = new SteamAudio.SceneSettings();
    private final Map<MaterialKey, SteamAudio.Material> materials = new HashMap<>();
    private final int flags;
    private final boolean gpu;
    private final PointerByReference openCL = new PointerByReference(), radeon = new PointerByReference(), mesh = new PointerByReference();
    private Memory vertexData, triangleData, materialIndices;
    private SteamAudio.Material[] gpuMaterials;
    /** The parts the uploaded scene mesh was combined from. */
    private AcousticMesh.Data uploadedTerrain, uploadedStructures;
    private BiFunction<Vec3, Vec3, AcousticRay> tracer;
    private Vec3 offset = Vec3.ZERO;
    private Throwable callbackFailure;

    public SteamSimulation(int rate, int flags) {
        this(rate, flags, false);
    }

    public SteamSimulation(int rate, int flags, boolean gpu) {
        this.flags = flags;
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
            // Volumetric occlusion (AcousticDiffraction) on the CPU direct path.
            if ((flags & 1) != 0 && !gpu) settings.occlusionSamples = AcousticDiffraction.MAX_SAMPLES;
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

    public boolean gpu() { return gpu; }

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

    private void upload(AcousticMesh.Data data) {
        var gpuApi = SteamGpu.api();
        if (mesh.getValue() != null) {
            gpuApi.iplStaticMeshRemove(mesh.getValue(), scene.getValue());
            gpuApi.iplStaticMeshRelease(mesh);
            mesh.setValue(null);
            vertexData = null;
            triangleData = null;
            materialIndices = null;
            gpuMaterials = null;
        }
        offset = data.origin();
        if (data.triangles().length > 0) {
            vertexData = new Memory(data.vertices().length * 4L);
            triangleData = new Memory(data.triangles().length * 4L);
            materialIndices = new Memory(data.materials().length * 4L);
            vertexData.write(0, data.vertices(), 0, data.vertices().length);
            triangleData.write(0, data.triangles(), 0, data.triangles().length);
            materialIndices.write(0, data.materials(), 0, data.materials().length);
            gpuMaterials = (SteamAudio.Material[]) new SteamAudio.Material().toArray(AcousticMaterials.COUNT);
            for (int i = 0; i < AcousticMaterials.COUNT; i++) {
                // JNA toArray reads the contiguous native backing memory into new elements;
                // field initializers on Material are not retained for every array element.
                gpuMaterials[i].absorption = AcousticMaterials.absorption(i);
                gpuMaterials[i].scattering = AcousticMaterials.GPU_SCATTERING;
                gpuMaterials[i].transmission = AcousticMaterials.transmission(i, 1);
                gpuMaterials[i].write();
            }
            var settings = new SteamGpu.MeshSettings();
            settings.vertices = data.vertices().length / 3;
            settings.triangles = data.triangles().length / 3;
            settings.materials = AcousticMaterials.COUNT;
            settings.vertexData = vertexData;
            settings.triangleData = triangleData;
            settings.materialIndices = materialIndices;
            settings.materialData = gpuMaterials[0].getPointer();
            SteamAudio.check(gpuApi.iplStaticMeshCreate(scene.getValue(), settings, mesh), "GPU mesh upload");
            gpuApi.iplStaticMeshAdd(mesh.getValue(), scene.getValue());
        }
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

    private SteamAudio.SimulationOutputs run(Vec3 listenerWorld, Vec3 sourceWorld, int rays, int bounces) {
        callbackFailure = null;
        var inputs = new SteamAudio.SimulationInputs();
        inputs.flags = flags;
        Vec3 relative = sourceWorld.subtract(offset);
        inputs.source.origin = new SteamAudio.Vector(relative.x, relative.y, relative.z);
        boolean volumetric = (flags & 1) != 0 && occludeVolumetrically(inputs);
        // The voxel scene traces its own transmission (AcousticDirectTransmission).
        boolean traced = (flags & 1) != 0 && !gpu && tracer != null;
        if (traced) inputs.directFlags &= ~16;
        api.iplSourceSetInputs(source.getValue(), flags, inputs);
        var shared = new SteamAudio.SharedInputs();
        shared.rays = gpu ? GPU_RAYS : rays;
        shared.bounces = bounces;
        shared.order = 1;
        Vec3 listener = listenerWorld.subtract(offset);
        shared.listener.origin = new SteamAudio.Vector(listener.x, listener.y, listener.z);
        api.iplSimulatorSetSharedInputs(simulator.getValue(), flags, shared);
        if ((flags & 1) != 0) api.iplSimulatorRunDirect(simulator.getValue());
        // Radeon Rays cannot trace an empty acceleration structure. Open air has no wet response.
        boolean reflect = (flags & 2) != 0 && (!gpu || mesh.getValue() != null);
        if (reflect) api.iplSimulatorRunReflections(simulator.getValue());
        if (callbackFailure != null) throw new IllegalStateException("Acoustic geometry callback failed", callbackFailure);
        var outputs = new SteamAudio.SimulationOutputs();
        api.iplSourceGetOutputs(source.getValue(), flags, outputs);
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
        if (source.getValue() != null) {
            api.iplSourceRemove(source.getValue(), simulator.getValue());
            api.iplSourceRelease(source);
        }
        if (simulator.getValue() != null) api.iplSimulatorRelease(simulator);
        if (mesh.getValue() != null) SteamGpu.api().iplStaticMeshRelease(mesh);
        if (scene.getValue() != null) api.iplSceneRelease(scene);
        if (radeon.getValue() != null) SteamGpu.api().iplRadeonRaysDeviceRelease(radeon);
        if (openCL.getValue() != null) SteamGpu.api().iplOpenCLDeviceRelease(openCL);
        if (context.getValue() != null) api.iplContextRelease(context);
    }
}
