package dimblend.radio.acoustics;

import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import java.util.function.Function;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Worker-owned mesh: listener motion inside a padded region does not change world geometry. */
public final class ReflectionMeshCache {
    /** Geometry kept around the listener-source box: reflection paths leave it through walls this far out. */
    public static final double MARGIN = 32;
    /** Extra mesh beyond {@link #MARGIN}, so camera motion reuses the uploaded scene. */
    public static final double PADDING = 16;
    private Object scene;
    private TerrainGeometryMode mode;
    private AABB bounds;
    private AcousticMesh.Data mesh;

    /** World region a rebuild for these endpoints reads; snapshots must cover it. */
    public static AABB region(Vec3 listener, Vec3 source) {
        return new AABB(listener, source).inflate(MARGIN + PADDING);
    }

    public AcousticMesh.Data get(Object snapshot, Vec3 listener, Vec3 source, Function<AABB, AcousticMesh.Data> build) {
        AABB required = new AABB(listener, source).inflate(MARGIN);
        if (mesh == null || mode != TerrainGeometryMode.CURRENT
                || !AcousticUpdateGate.sameGeometry(scene, snapshot) || !AcousticSnapshot.contains(bounds, required)) {
            AABB expanded = required.inflate(PADDING);
            AcousticMesh.Data rebuilt = build.apply(expanded);
            bounds = expanded;
            mesh = rebuilt;
            mode = TerrainGeometryMode.CURRENT;
            // Compare later scenes with the one this mesh was built from, not the previous call's:
            // otherwise motion below the pose tolerance per call drifts without bound.
            scene = snapshot;
        }
        return mesh;
    }
}
