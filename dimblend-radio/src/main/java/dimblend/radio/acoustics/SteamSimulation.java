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
    private record MaterialKey(float reflectivity, int thicknessStep) { }
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
        return run(listenerWorld, sourceWorld, rays, bounces);
    }

    private SteamAudio.SimulationOutputs run(Vec3 listenerWorld, Vec3 sourceWorld, int rays, int bounces) {
        callbackFailure = null;
        var inputs = new SteamAudio.SimulationInputs();
        inputs.flags = flags;
        Vec3 relative = sourceWorld.subtract(offset);
        inputs.source.origin = new SteamAudio.Vector(relative.x, relative.y, relative.z);
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
        outputs.direct.flags = 27;
        outputs.direct.transmissionType = 1;
        return outputs;
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
                Pointer surface = material(result.reflectivity(), result.thickness()).getPointer();
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
        try { output.setByte(0, (byte) (max <= min || cast(ray.getFloatArray(0, 6), min, max).kind() != AcousticRay.Kind.MISS ? 1 : 0)); }
        catch (Throwable error) { callbackFailure = error; output.setByte(0, (byte) 1); }
    }

    /**
     * Absorption follows the block's own reflectivity; transmission follows its material class and
     * the path length through the wall the ray entered (the solver multiplies one value per hit).
     */
    private SteamAudio.Material material(float reflectivity, float thickness) {
        var key = new MaterialKey(reflectivity, AcousticMaterials.thicknessStep(thickness));
        return materials.computeIfAbsent(key, value -> {
            var material = new SteamAudio.Material();
            float absorption = Math.max(0.02f, 1 - value.reflectivity());
            material.absorption = new float[] {absorption * 0.4f, absorption * 0.6f,
                    Math.min(0.98f, absorption * 1.2f)};
            // The SDK seeds diffuse scattering from wall-clock time. Voxel surface normals
            // already provide geometric scattering; keep CPU material paths repeatable.
            material.scattering = 0;
            material.transmission = AcousticMaterials.transmission(AcousticMaterials.bucket(value.reflectivity()),
                    AcousticMaterials.thickness(value.thicknessStep()));
            material.write();
            return material;
        });
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
