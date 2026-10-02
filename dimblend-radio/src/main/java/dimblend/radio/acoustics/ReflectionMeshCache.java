package dimblend.radio.acoustics;

import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import java.util.function.Function;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Worker-owned mesh: listener motion inside a padded region does not change world geometry. */
public final class ReflectionMeshCache {
    private Object scene;
    private TerrainGeometryMode mode;
    private AABB bounds;
    private AcousticMesh.Data mesh;

    public AcousticMesh.Data get(Object snapshot, Vec3 listener, Vec3 source, Function<AABB, AcousticMesh.Data> build) {
        AABB required = new AABB(listener, source).inflate(24);
        if (mesh == null || mode != TerrainGeometryMode.CURRENT
                || !AcousticUpdateGate.sameGeometry(scene, snapshot) || !contains(bounds, required)) {
            AABB expanded = required.inflate(8);
            AcousticMesh.Data rebuilt = build.apply(expanded);
            bounds = expanded;
            mesh = rebuilt;
            mode = TerrainGeometryMode.CURRENT;
        }
        scene = snapshot;
        return mesh;
    }

    private static boolean contains(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }
}
