package dimblend.radio.acoustics;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.Path;

/** Steam Audio 4.8.1 C ABI. Values and field order follow the distributed phonon.h. */
public final class SteamAudio {
    public interface Api extends Library {
        int iplContextCreate(ContextSettings settings, PointerByReference context);
        void iplContextRelease(PointerByReference context);
        int iplSceneCreate(Pointer context, SceneSettings settings, PointerByReference scene);
        void iplSceneCommit(Pointer scene);
        void iplSceneRelease(PointerByReference scene);
        int iplSimulatorCreate(Pointer context, SimulationSettings settings, PointerByReference simulator);
        void iplSimulatorSetScene(Pointer simulator, Pointer scene);
        void iplSimulatorSetSharedInputs(Pointer simulator, int flags, SharedInputs inputs);
        void iplSimulatorCommit(Pointer simulator);
        void iplSimulatorRunDirect(Pointer simulator);
        void iplSimulatorRunReflections(Pointer simulator);
        void iplSimulatorRelease(PointerByReference simulator);
        int iplSourceCreate(Pointer simulator, SourceSettings settings, PointerByReference source);
        void iplSourceAdd(Pointer source, Pointer simulator);
        void iplSourceSetInputs(Pointer source, int flags, SimulationInputs inputs);
        void iplSourceGetOutputs(Pointer source, int flags, SimulationOutputs outputs);
        void iplSourceRemove(Pointer source, Pointer simulator);
        void iplSourceRelease(PointerByReference source);
        int iplDirectEffectCreate(Pointer context, AudioSettings audio, DirectSettings settings, PointerByReference effect);
        int iplDirectEffectApply(Pointer effect, DirectParams params, AudioBuffer in, AudioBuffer out);
        void iplDirectEffectReset(Pointer effect);
        void iplDirectEffectRelease(PointerByReference effect);
        int iplReflectionEffectCreate(Pointer context, AudioSettings audio, ReflectionSettings settings, PointerByReference effect);
        int iplReflectionEffectApply(Pointer effect, ReflectionParams params, AudioBuffer in, AudioBuffer out, Pointer mixer);
        void iplReflectionEffectReset(Pointer effect);
        void iplReflectionEffectRelease(PointerByReference effect);
        int iplReflectionEffectGetTailSize(Pointer effect);
        int iplReflectionEffectGetTail(Pointer effect, AudioBuffer out, Pointer mixer);
        int iplPanningEffectCreate(Pointer context, AudioSettings audio, PanningSettings settings, PointerByReference effect);
        int iplPanningEffectApply(Pointer effect, PanningParams params, AudioBuffer in, AudioBuffer out);
        void iplPanningEffectReset(Pointer effect);
        void iplPanningEffectRelease(PointerByReference effect);
        int iplAmbisonicsDecodeEffectCreate(Pointer context, AudioSettings audio, DecodeSettings settings, PointerByReference effect);
        int iplAmbisonicsDecodeEffectApply(Pointer effect, DecodeParams params, AudioBuffer in, AudioBuffer out);
        void iplAmbisonicsDecodeEffectReset(Pointer effect);
        void iplAmbisonicsDecodeEffectRelease(PointerByReference effect);
    }
    public interface ClosestHit extends Callback { void invoke(Pointer ray, float min, float max, Pointer hit, Pointer user); }
    public interface AnyHit extends Callback { void invoke(Pointer ray, float min, float max, Pointer occluded, Pointer user); }

    @FieldOrder({"version", "log", "allocate", "free", "simd", "flags"})
    public static class ContextSettings extends Structure {
        public int version = 0x040801;
        public Pointer log, allocate, free;
        /**
         * Highest SIMD level Steam Audio may pick for this process (it falls back to what the CPU
         * supports). 0 would pin SSE2; AVX2 is the header's recommended ceiling, avoiding AVX-512 throttling.
         */
        public int simd = 3;
        public int flags;
    }
    @FieldOrder({"x", "y", "z"})
    public static class Vector extends Structure {
        public float x, y, z;
        public Vector() { }
        public Vector(double x, double y, double z) { set(x, y, z); }
        public void set(double x, double y, double z) { this.x = (float) x; this.y = (float) y; this.z = (float) z; }
        /**
         * Copies values. Assigning a Structure to an embedded field instead re-points that object's
         * memory into the new parent, which breaks any other struct still embedding it.
         */
        public void set(Vector other) { x = other.x; y = other.y; z = other.z; }
    }
    @FieldOrder({"right", "up", "ahead", "origin"})
    public static class Space extends Structure {
        public Vector right = new Vector(1, 0, 0), up = new Vector(0, 1, 0), ahead = new Vector(0, 0, -1), origin = new Vector();
        public void set(Space other) {
            right.set(other.right);
            up.set(other.up);
            ahead.set(other.ahead);
            origin.set(other.origin);
        }
    }
    @FieldOrder({"origin", "direction"})
    public static class Ray extends Structure {
        public Vector origin = new Vector(), direction = new Vector();
        public Ray(Pointer pointer) { super(pointer); read(); }
    }
    @FieldOrder({"absorption", "scattering", "transmission"})
    public static class Material extends Structure {
        public float[] absorption = new float[3];
        public float scattering;
        public float[] transmission = {0.35f, 0.2f, 0.08f};
    }
    @FieldOrder({"distance", "triangle", "object", "materialIndex", "normal", "material"})
    public static class Hit extends Structure {
        public float distance = Float.POSITIVE_INFINITY;
        public int triangle = -1, object = -1, materialIndex = -1;
        public Vector normal = new Vector();
        public Pointer material;
        public Hit(Pointer pointer) { super(pointer); }
    }
    @FieldOrder({"type", "closest", "any", "batchClosest", "batchAny", "user", "embree", "radeon"})
    public static class SceneSettings extends Structure {
        public int type = 3;
        public ClosestHit closest;
        public AnyHit any;
        public Pointer batchClosest, batchAny, user, embree, radeon;
    }
    @FieldOrder({"samplingRate", "frameSize"})
    public static class AudioSettings extends Structure { public int samplingRate, frameSize = SteamRenderer.FRAME; }
    @FieldOrder({"channels", "samples", "data"})
    public static class AudioBuffer extends Structure {
        public int channels = 1, samples = SteamRenderer.FRAME;
        public Pointer data;
        private final Memory[] storage;
        private final Memory pointers;
        public AudioBuffer() { this(1); }
        public AudioBuffer(int channels) {
            this.channels = channels;
            storage = new Memory[channels];
            pointers = new Memory((long) Native.POINTER_SIZE * channels);
            for (int i = 0; i < channels; i++) {
                storage[i] = new Memory(SteamRenderer.FRAME * 4L);
                pointers.setPointer((long) i * Native.POINTER_SIZE, storage[i]);
            }
            data = pointers;
        }
        public Memory memory(int channel) { return storage[channel]; }
    }
    @FieldOrder({"channels"})
    public static class DirectSettings extends Structure { public int channels = 1; }
    @FieldOrder({"flags", "transmissionType", "distance", "air", "directivity", "occlusion", "transmission"})
    public static class DirectParams extends Structure {
        public int flags = 27, transmissionType = 1;
        public float distance = 1;
        public float[] air = {1, 1, 1};
        public float directivity = 1, occlusion = 1;
        public float[] transmission = {0, 0, 0};
    }
    @FieldOrder({"type", "irSize", "channels"})
    public static class ReflectionSettings extends Structure { public int type, irSize, channels = 1; }
    @FieldOrder({"type", "ir", "reverbTimes", "eq", "delay", "channels", "irSize", "tanDevice", "tanSlot"})
    public static class ReflectionParams extends Structure {
        public int type;
        public Pointer ir;
        public float[] reverbTimes = new float[3], eq = new float[3];
        public int delay, channels, irSize;
        public Pointer tanDevice;
        public int tanSlot;
    }
    @FieldOrder({"type", "minimum", "callback", "user", "dirty"})
    public static class DistanceModel extends Structure {
        public int type = 1;
        public float minimum = 1;
        public Pointer callback, user;
        public int dirty;
    }
    @FieldOrder({"type", "coefficients", "callback", "user", "dirty"})
    public static class AirModel extends Structure {
        public int type;
        public float[] coefficients = new float[3];
        public Pointer callback, user;
        public int dirty;
    }
    @FieldOrder({"weight", "power", "callback", "user"})
    public static class Directivity extends Structure { public float weight, power; public Pointer callback, user; }
    @FieldOrder({"center", "radius"})
    public static class Sphere extends Structure { public Vector center = new Vector(); public float radius; }
    @FieldOrder({"type", "variation", "sphere"})
    public static class BakedId extends Structure { public int type, variation; public Sphere sphere = new Sphere(); }
    @FieldOrder({"flags", "directFlags", "source", "distance", "air", "directivity", "occlusionType", "occlusionRadius", "occlusionSamples", "reverbScale", "transition", "overlap", "baked", "bakedId", "probes", "visRadius", "visThreshold", "visRange", "pathOrder", "validation", "alternate", "transmissionRays", "deviation"})
    public static class SimulationInputs extends Structure {
        public int flags = 3, directFlags = 27;
        public Space source = new Space();
        public DistanceModel distance = new DistanceModel();
        public AirModel air = new AirModel();
        public Directivity directivity = new Directivity();
        public int occlusionType;
        public float occlusionRadius = 0.5f;
        public int occlusionSamples = 1;
        public float[] reverbScale = {1, 1, 1};
        public float transition, overlap;
        public int baked;
        public BakedId bakedId = new BakedId();
        public Pointer probes;
        public float visRadius, visThreshold, visRange;
        public int pathOrder, validation, alternate, transmissionRays = 8;
        public Pointer deviation;
    }
    @FieldOrder({"flags", "sceneType", "reflectionType", "occlusionSamples", "maxRays", "diffuseSamples", "duration", "order", "sources", "threads", "batch", "visSamples", "samplingRate", "frameSize", "openCL", "radeon", "tan"})
    public static class SimulationSettings extends Structure {
        public int flags = 3, sceneType = 3, reflectionType, occlusionSamples = 1, maxRays = 128, diffuseSamples = 128;
        public float duration = 6;
        public int order, sources = 1, threads = 1, batch = 1, visSamples = 1, samplingRate, frameSize = SteamRenderer.FRAME;
        public Pointer openCL, radeon, tan;
    }
    @FieldOrder({"listener", "rays", "bounces", "duration", "order", "minimum", "callback", "user"})
    public static class SharedInputs extends Structure {
        public Space listener = new Space();
        public int rays = 32, bounces = 128;
        public float duration = 6;
        public int order;
        public float minimum = 1;
        public Pointer callback, user;
    }
    @FieldOrder({"flags"})
    public static class SourceSettings extends Structure { public int flags = 3; }
    @FieldOrder({"eq", "coefficients", "order", "binaural", "hrtf", "listener", "normalize"})
    public static class PathParams extends Structure {
        public float[] eq = new float[3];
        public Pointer coefficients;
        public int order, binaural;
        public Pointer hrtf;
        public Space listener = new Space();
        public int normalize;
    }
    @FieldOrder({"direct", "reflections", "pathing"})
    public static class SimulationOutputs extends Structure {
        public DirectParams direct = new DirectParams();
        public ReflectionParams reflections = new ReflectionParams();
        public PathParams pathing = new PathParams();
    }
    @FieldOrder({"type", "speakers", "positions"})
    public static class SpeakerLayout extends Structure { public int type = 1, speakers; public Pointer positions; }
    @FieldOrder({"layout"})
    public static class PanningSettings extends Structure { public SpeakerLayout layout = new SpeakerLayout(); }
    @FieldOrder({"direction"})
    public static class PanningParams extends Structure { public Vector direction = new Vector(0, 0, -1); }
    @FieldOrder({"layout", "hrtf", "order"})
    public static class DecodeSettings extends Structure {
        public SpeakerLayout layout = new SpeakerLayout();
        public Pointer hrtf;
        public int order = 1;
    }
    @FieldOrder({"order", "hrtf", "orientation", "binaural"})
    public static class DecodeParams extends Structure {
        public int order = 1;
        public Pointer hrtf;
        public Space orientation = new Space();
        public int binaural;
    }

    private static Api api;
    public static synchronized Api api() {
        if (api == null) {
            if (!System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64")) {
                throw new UnsupportedOperationException("Bundled Steam Audio currently targets Windows x64");
            }
            api = Native.load(library().toString(), Api.class);
        }
        return api;
    }

    /** The extracted phonon.dll every Steam Audio binding loads. */
    static Path library() {
        return SteamNativeLibraries.library("phonon.dll");
    }
    public static void check(int status, String operation) {
        if (status != 0) throw new IllegalStateException(operation + " failed: " + status);
    }
    private SteamAudio() { }
}
