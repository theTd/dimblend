package dimblend.radio.acoustics;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * A triangle mesh added to a Steam Audio scene (Radeon Rays or Embree) with the acoustic material
 * table, keeping the native buffers it was created from alive until it is closed.
 */
public final class SteamStaticMesh implements AutoCloseable {
    private final SteamGpu.Api api = SteamGpu.api();
    private final Pointer scene;
    private final PointerByReference mesh = new PointerByReference();
    private final Memory vertexData, triangleData, materialIndices;
    private final SteamAudio.Material[] materials;

    /**
     * Creates the mesh from {@code data} (vertices relative to its origin) and adds it to
     * {@code scene}; the caller commits the scene.
     *
     * @param scatteringOverride scattering for every material
     */
    public SteamStaticMesh(Pointer scene, AcousticMesh.Data data, float scatteringOverride) {
        if (data.triangleCount() == 0) throw new IllegalArgumentException("A static mesh needs triangles");
        this.scene = scene;
        vertexData = new Memory(data.vertices().length * 4L);
        triangleData = new Memory(data.triangles().length * 4L);
        materialIndices = new Memory(data.materials().length * 4L);
        vertexData.write(0, data.vertices(), 0, data.vertices().length);
        triangleData.write(0, data.triangles(), 0, data.triangles().length);
        materialIndices.write(0, data.materials(), 0, data.materials().length);
        materials = (SteamAudio.Material[]) new SteamAudio.Material().toArray(AcousticMaterials.COUNT);
        for (int i = 0; i < AcousticMaterials.COUNT; i++) {
            // JNA toArray reads the contiguous native backing memory into new elements;
            // field initializers on Material are not retained for every array element.
            materials[i].absorption = AcousticMaterials.absorption(i);
            materials[i].scattering = scatteringOverride;
            materials[i].transmission = AcousticMaterials.transmission(i, 1);
            materials[i].write();
        }
        var settings = new SteamGpu.MeshSettings();
        settings.vertices = data.vertices().length / 3;
        settings.triangles = data.triangles().length / 3;
        settings.materials = AcousticMaterials.COUNT;
        settings.vertexData = vertexData;
        settings.triangleData = triangleData;
        settings.materialIndices = materialIndices;
        settings.materialData = materials[0].getPointer();
        SteamAudio.check(api.iplStaticMeshCreate(scene, settings, mesh), "static mesh");
        api.iplStaticMeshAdd(mesh.getValue(), scene);
    }

    /** Removes the mesh from its scene and releases it; the caller commits the scene. */
    @Override public void close() {
        if (mesh.getValue() == null) return;
        api.iplStaticMeshRemove(mesh.getValue(), scene);
        api.iplStaticMeshRelease(mesh);
        mesh.setValue(null);
    }
}
