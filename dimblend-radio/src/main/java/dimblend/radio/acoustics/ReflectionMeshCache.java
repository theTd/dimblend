package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Worker-owned reflection scene, kept as two parts. Terrain is meshed for a padded region around the
 * listener and every simulated radio, and kept until they leave it, a section inside it changes or
 * the set of radios changes; moving structures are meshed once in their own frame and only
 * re-placed when they move, so a passing train never remeshes the world.
 */
public final class ReflectionMeshCache {
    /** Geometry kept around the listener-source box: reflection paths leave it through walls this far out. */
    public static final double MARGIN = 32;
    /** Extra mesh beyond {@link #MARGIN}, so camera motion reuses the uploaded scene. */
    public static final double PADDING = 16;

    /** Static terrain and placed structures, both relative to {@code terrain.origin()}. */
    public record Scene(AcousticMesh.Data terrain, AcousticMesh.Data structures) {
        public int triangleCount() { return terrain.triangleCount() + structures.triangleCount(); }
    }

    private record LocalMesh(long contentKey, AcousticMesh.Data data) { }
    private record Placement(UUID id, long contentKey, Pose3d pose) { }

    private final AcousticMesh.Workspace workspace = new AcousticMesh.Workspace();
    private final Map<UUID, LocalMesh> localMeshes = new HashMap<>();
    private AABB bounds;
    /** The radios whose cells the terrain mesh left open. */
    private Set<BlockPos> emitters = Set.of();
    private AcousticMesh.Data terrain;
    /** Every terrain section the mesh region spans, with its state when the mesh was built. */
    private long[] sectionKeys = new long[0], sectionStates = new long[0];
    private List<Placement> placements = List.of();
    private AcousticMesh.Data structures;
    private Scene scene;

    /** World region a rebuild for these endpoints reads; snapshots must cover it. */
    public static AABB region(Vec3 listener, Vec3 source) {
        return new AABB(listener, source).inflate(MARGIN + PADDING);
    }

    /**
     * The scene for one listener and every source simulated against it: their regions are merged,
     * so a run for several radios meshes and uploads one scene.
     */
    public Scene get(ReflectionGeometry geometry, Vec3 listener, List<Vec3> sources) {
        AABB required = new AABB(listener, listener);
        for (Vec3 source : sources) required = required.minmax(new AABB(source, source));
        required = required.inflate(MARGIN);
        if (terrain == null || !AcousticSnapshot.contains(bounds, required) || !emitters.equals(geometry.emitters())
                || terrainChanged(geometry)) {
            AABB expanded = required.inflate(PADDING);
            Vec3 center = expanded.getCenter();
            Vec3 origin = new Vec3(Math.floor(center.x / 16) * 16, Math.floor(center.y / 16) * 16, Math.floor(center.z / 16) * 16);
            terrain = geometry.terrainMesh(expanded, origin, workspace);
            bounds = expanded;
            emitters = geometry.emitters();
            recordSections(geometry, expanded);
        }
        AcousticMesh.Data placed = place(geometry.bodies());
        if (scene == null || scene.terrain != terrain || scene.structures != placed) scene = new Scene(terrain, placed);
        return scene;
    }

    /**
     * Sections the current snapshot did not capture cannot be checked and are taken as unchanged;
     * one captured now but not when the mesh was built reads as a change, so it gets meshed.
     */
    private boolean terrainChanged(ReflectionGeometry geometry) {
        for (int i = 0; i < sectionKeys.length; i++) {
            long state = geometry.terrainSection(sectionKeys[i]);
            if (state != ReflectionGeometry.UNCAPTURED && state != sectionStates[i]) return true;
        }
        return false;
    }

    private void recordSections(ReflectionGeometry geometry, AABB box) {
        sectionKeys = geometry.sectionKeys(box);
        sectionStates = geometry.terrainSections(sectionKeys);
    }

    /**
     * Structures as placed now. Placements are compared with the ones the current mesh was made
     * from, not the previous call's: motion below the pose tolerance per call must not drift.
     */
    private AcousticMesh.Data place(List<? extends ReflectionGeometry.Body> bodies) {
        if (structures != null && structures.origin().equals(terrain.origin()) && samePlacements(bodies)) return structures;
        Map<UUID, LocalMesh> kept = new HashMap<>();
        List<Placement> placed = new ArrayList<>(bodies.size());
        AcousticMesh mesh = new AcousticMesh(terrain.origin(), workspace);
        for (ReflectionGeometry.Body body : bodies) {
            LocalMesh local = localMeshes.get(body.id());
            if (local == null || local.contentKey != body.contentKey()) {
                local = new LocalMesh(body.contentKey(), body.localMesh(workspace));
            }
            kept.put(body.id(), local);
            mesh.appendPlaced(local.data, body.pose());
            placed.add(new Placement(body.id(), body.contentKey(), new Pose3d(body.pose())));
        }
        localMeshes.clear();
        localMeshes.putAll(kept);
        placements = List.copyOf(placed);
        structures = mesh.data();
        return structures;
    }

    private boolean samePlacements(List<? extends ReflectionGeometry.Body> bodies) {
        if (bodies.size() != placements.size()) return false;
        outer:
        for (ReflectionGeometry.Body body : bodies) {
            for (Placement placement : placements) {
                if (placement.id.equals(body.id())) {
                    if (placement.contentKey != body.contentKey() || !AcousticUpdateGate.samePose(placement.pose, body.pose())) return false;
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }
}
