package dimblend.radio.acoustics.bake;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import dimblend.radio.acoustics.AcousticMaterials;
import dimblend.radio.acoustics.AcousticMesh;
import dimblend.radio.acoustics.AcousticPathing;
import dimblend.radio.acoustics.SteamAudio;
import dimblend.radio.acoustics.SteamBaking;
import dimblend.radio.acoustics.SteamStaticMesh;
import java.lang.ref.Reference;
import java.util.function.DoubleConsumer;

/**
 * Bakes pathing probe batches on an Embree scene in a context of its own. One baker per process
 * (Steam Audio runs one bake at a time); bakes run to the end, as the SDK's cancel faults.
 */
public final class PathingBaker implements AutoCloseable {
    private final SteamBaking.Api api = SteamBaking.api();
    private final PointerByReference context = new PointerByReference();
    private final PointerByReference embree = new PointerByReference();

    public PathingBaker() {
        try {
            SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "bake context");
            SteamAudio.check(api.iplEmbreeDeviceCreate(context.getValue(), null, embree), "Embree device");
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    /**
     * @param mesh the region's geometry; the probes are stored relative to its origin, which is
     *     the frame the runtime traces in
     * @param probes centres in world coordinates
     * @param pathRange longest path kept, in blocks
     * @param progress fraction done, called from the bake threads
     * @return the batch as {@code iplProbeBatchSave} wrote it
     */
    public byte[] bake(AcousticMesh.Data mesh, PathingProbePlacement.Probes probes, float pathRange, int threads,
            DoubleConsumer progress) {
        if (probes.count() == 0) throw new IllegalArgumentException("No probes to bake");
        var sceneSettings = new SteamAudio.SceneSettings();
        sceneSettings.type = 1;
        sceneSettings.embree = embree.getValue();
        var scene = new PointerByReference();
        var batch = new PointerByReference();
        var object = new PointerByReference();
        SteamStaticMesh triangles = null;
        try {
            SteamAudio.check(api.iplSceneCreate(context.getValue(), sceneSettings, scene), "bake scene");
            // Scattering does not enter pathing; keep the reflection table's value.
            if (mesh.triangleCount() > 0) triangles = new SteamStaticMesh(scene.getValue(), mesh, AcousticMaterials.GPU_SCATTERING);
            api.iplSceneCommit(scene.getValue());
            SteamAudio.check(api.iplProbeBatchCreate(context.getValue(), batch), "probe batch");
            double[] centres = probes.centres();
            for (int i = 0; i < probes.count(); i++) {
                var probe = new SteamBaking.ProbeSphere();
                probe.center.set(centres[i * 3] - mesh.origin().x, centres[i * 3 + 1] - mesh.origin().y,
                        centres[i * 3 + 2] - mesh.origin().z);
                probe.radius = probes.radius();
                api.iplProbeBatchAddProbe(batch.getValue(), probe);
            }
            api.iplProbeBatchCommit(batch.getValue());
            var params = new SteamBaking.PathBakeParams();
            params.scene = scene.getValue();
            params.probeBatch = batch.getValue();
            params.identifier = SteamBaking.pathingLayer();
            params.numSamples = AcousticPathing.VISIBILITY_SAMPLES;
            params.radius = AcousticPathing.SAMPLE_RADIUS;
            params.threshold = AcousticPathing.VISIBILITY_THRESHOLD;
            params.visRange = AcousticPathing.VISIBILITY_RANGE;
            params.pathRange = pathRange;
            params.numThreads = Math.max(1, threads);
            SteamBaking.Progress callback = (fraction, user) -> {
                try { progress.accept(fraction); } catch (RuntimeException ignored) { }
            };
            api.iplPathBakerBake(context.getValue(), params, callback, null);
            Reference.reachabilityFence(callback);
            if (api.iplProbeBatchGetDataSize(batch.getValue(), SteamBaking.pathingLayer()) <= 0) {
                throw new IllegalStateException("The pathing bake stored nothing");
            }
            SteamAudio.check(api.iplSerializedObjectCreate(context.getValue(), new SteamAudio.SerializedObjectSettings(), object),
                    "serialized pathing");
            api.iplProbeBatchSave(batch.getValue(), object.getValue());
            long size = api.iplSerializedObjectGetSize(object.getValue());
            if (size <= 0 || size > Integer.MAX_VALUE) throw new IllegalStateException("Pathing bake of " + size + " bytes");
            Pointer data = api.iplSerializedObjectGetData(object.getValue());
            return data.getByteArray(0, (int) size);
        } finally {
            if (object.getValue() != null) api.iplSerializedObjectRelease(object);
            if (batch.getValue() != null) api.iplProbeBatchRelease(batch);
            if (triangles != null) triangles.close();
            if (scene.getValue() != null) api.iplSceneRelease(scene);
        }
    }

    @Override public void close() {
        if (embree.getValue() != null) api.iplEmbreeDeviceRelease(embree);
        if (context.getValue() != null) api.iplContextRelease(context);
    }
}
