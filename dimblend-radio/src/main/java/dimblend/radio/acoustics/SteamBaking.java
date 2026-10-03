package dimblend.radio.acoustics;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.PointerByReference;

/**
 * Steam Audio 4.8.1 baking ABI (probe batches, Embree scenes, the path baker); the runtime side
 * (loading batches, running pathing, the path effect) is in {@link SteamAudio}.
 * <p>
 * {@code iplPathBakerCancelBake} is deliberately not bound: in 4.8.1 the call returns and the
 * baking thread then faults, taking the process down. Path bakes must be sized to run to the end.
 */
public final class SteamBaking {
    public interface Api extends SteamGpu.Api {
        int iplEmbreeDeviceCreate(Pointer context, Pointer settings, PointerByReference device);
        void iplEmbreeDeviceRelease(PointerByReference device);
        int iplProbeBatchCreate(Pointer context, PointerByReference probeBatch);
        void iplProbeBatchAddProbe(Pointer probeBatch, ProbeSphere probe);
        long iplProbeBatchGetDataSize(Pointer probeBatch, SteamAudio.BakedId identifier);
        void iplProbeBatchSave(Pointer probeBatch, Pointer object);
        /** {@code progress} must not be null: without it the bake returns at once and stores nothing. */
        void iplPathBakerBake(Pointer context, PathBakeParams params, Progress progress, Pointer user);
    }

    /** Called from the baking threads with the fraction done. */
    public interface Progress extends Callback { void invoke(float progress, Pointer user); }

    /** {@code IPLSphere} passed by value. */
    public static class ProbeSphere extends SteamAudio.Sphere implements Structure.ByValue { }

    @FieldOrder({"scene", "probeBatch", "identifier", "numSamples", "radius", "threshold", "visRange", "pathRange", "numThreads"})
    public static class PathBakeParams extends Structure {
        public Pointer scene, probeBatch;
        public SteamAudio.BakedId identifier = new SteamAudio.BakedId();
        /** Points sampled around each probe; numSamples² rays decide whether two probes see each other. */
        public int numSamples;
        public float radius, threshold, visRange, pathRange;
        public int numThreads;
    }

    /** The identifier of a pathing layer: type {@code PATHING}, variation {@code DYNAMIC}. */
    public static SteamAudio.BakedId pathingLayer() {
        var identifier = new SteamAudio.BakedId();
        identifier.type = 1;
        identifier.variation = 3;
        return identifier;
    }

    private static Api api;
    public static synchronized Api api() {
        if (api == null) {
            SteamGpu.api();
            api = Native.load(SteamAudio.library().toString(), Api.class);
        }
        return api;
    }

    private SteamBaking() { }
}
