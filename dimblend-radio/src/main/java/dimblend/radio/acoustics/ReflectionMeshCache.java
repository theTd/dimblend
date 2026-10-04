package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Worker-owned reflection scene, kept as two parts: a still one and what is placed into it.
 * Terrain is meshed for a padded region around the listener and every simulated radio, and kept
 * until they leave it, a section inside it changes or the set of radios changes; moving structures
 * are meshed once in their own frame and only re-placed when they move, so a passing train never
 * remeshes the world.
 * <p>
 * In the world's frame the terrain is the still part. In a structure's (the scene's
 * {@link ReflectionGeometry#frame()}: the listener rides it) that structure is, and the terrain is
 * placed into it like any other structure: the listener and the radios on board then keep their
 * positions in the simulation while the structure moves.
 */
public final class ReflectionMeshCache {
    /** Geometry kept around the listener-source box: reflection paths leave it through walls this far out. */
    public static final double MARGIN = 32;
    /** Extra mesh beyond {@link #MARGIN}, so camera motion reuses the uploaded scene. */
    public static final double PADDING = 16;

    /** The still part and the placed part, both relative to {@code fixed.origin()}, in the scene's frame. */
    public record Scene(AcousticMesh.Data fixed, AcousticMesh.Data placed) {
        public int triangleCount() { return fixed.triangleCount() + placed.triangleCount(); }
    }

    private record LocalMesh(long contentKey, AcousticMesh.Data data) { }
    /** @param id a structure, or null for the terrain; {@code contentKey} is then the terrain mesh's generation */
    private record Placement(UUID id, long contentKey, Pose3d pose) { }

    private final AcousticMesh.Workspace workspace = new AcousticMesh.Workspace();
    private final Map<UUID, LocalMesh> localMeshes = new HashMap<>();
    private AABB bounds;
    /** The radios whose cells the terrain mesh left open. */
    private Set<BlockPos> emitters = Set.of();
    private AcousticMesh.Data terrain;
    /** Bumped with every terrain mesh built. */
    private long terrainGeneration;
    /** Every terrain section the mesh region spans, with its state when the mesh was built. */
    private long[] sectionKeys = new long[0], sectionStates = new long[0];
    private List<Placement> placements = List.of();
    private AcousticMesh.Data placed;
    /** The still part of a frame whose structure has no surfaces near the listener. */
    private AcousticMesh.Data emptyFixed;
    private Scene scene;

    /** World region a rebuild for these endpoints reads; snapshots must cover it. */
    public static AABB region(Vec3 listener, Vec3 source) {
        return new AABB(listener, source).inflate(MARGIN + PADDING);
    }

    /**
     * The scene for one listener and every source simulated against it: their regions are merged,
     * so a run for several radios meshes and uploads one scene.
     *
     * @param listener and {@code sources} in world coordinates, whatever the scene's frame
     */
    public Scene get(ReflectionGeometry geometry, Vec3 listener, List<Vec3> sources) {
        AABB required = new AABB(listener, listener);
        for (Vec3 source : sources) required = required.minmax(new AABB(source, source));
        required = required.inflate(MARGIN);
        if (terrain == null || !AcousticSnapshot.contains(bounds, required) || !emitters.equals(geometry.emitters())
                || terrainChanged(geometry)) {
            AABB expanded = required.inflate(PADDING);
            terrain = geometry.terrainMesh(expanded, chunkCorner(expanded.getCenter()), workspace);
            terrainGeneration++;
            bounds = expanded;
            emitters = geometry.emitters();
            recordSections(geometry, expanded);
        }
        List<? extends ReflectionGeometry.Body> bodies = geometry.bodies();
        Set<UUID> present = new HashSet<>();
        for (ReflectionGeometry.Body body : bodies) present.add(body.id());
        localMeshes.keySet().retainAll(present);
        AcousticFrame frame = geometry.frame();
        ReflectionGeometry.Body carrier = frame.world() ? null : body(bodies, frame.structure());
        AcousticMesh.Data fixed = frame.world() ? terrain : carrier == null ? null : localMesh(carrier).data;
        if (fixed == null || fixed.triangleCount() == 0 && !frame.world()) fixed = emptyFixed(frame.toLocal(listener));
        List<Placement> wanted = new ArrayList<>(bodies.size() + 1);
        List<AcousticMesh.Data> meshes = new ArrayList<>(bodies.size() + 1);
        if (!frame.world()) {
            wanted.add(new Placement(null, terrainGeneration, frame.relative(null)));
            meshes.add(terrain);
        }
        for (ReflectionGeometry.Body body : bodies) {
            if (body == carrier) continue;
            wanted.add(new Placement(body.id(), body.contentKey(), frame.relative(body.pose())));
            meshes.add(localMesh(body).data);
        }
        AcousticMesh.Data next = place(fixed.origin(), wanted, meshes);
        if (scene == null || scene.fixed != fixed || scene.placed != next) scene = new Scene(fixed, next);
        return scene;
    }

    private static Vec3 chunkCorner(Vec3 point) {
        return new Vec3(Math.floor(point.x / 16) * 16, Math.floor(point.y / 16) * 16, Math.floor(point.z / 16) * 16);
    }

    private static ReflectionGeometry.Body body(List<? extends ReflectionGeometry.Body> bodies, UUID id) {
        for (ReflectionGeometry.Body body : bodies) if (body.id().equals(id)) return body;
        return null;
    }

    /** A structure's mesh in its own frame, voxelized again only when its blocks change. */
    private LocalMesh localMesh(ReflectionGeometry.Body body) {
        LocalMesh local = localMeshes.get(body.id());
        if (local == null || local.contentKey != body.contentKey()) {
            local = new LocalMesh(body.contentKey(), body.localMesh(workspace));
            localMeshes.put(body.id(), local);
        }
        return local;
    }

    /** Placement must not be relative to a far-off origin: floats keep about a centimetre at a million blocks. */
    private AcousticMesh.Data emptyFixed(Vec3 near) {
        Vec3 origin = chunkCorner(near);
        if (emptyFixed == null || !emptyFixed.origin().equals(origin)) emptyFixed = AcousticMesh.Data.empty(origin);
        return emptyFixed;
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
     * What moves in the frame, as placed now. Placements are compared with the ones the current
     * mesh was made from, not the previous call's: motion below the pose tolerance per call must
     * not drift.
     */
    private AcousticMesh.Data place(Vec3 origin, List<Placement> wanted, List<AcousticMesh.Data> meshes) {
        if (placed != null && placed.origin().equals(origin) && samePlacements(wanted)) return placed;
        AcousticMesh mesh = new AcousticMesh(origin, workspace);
        for (int i = 0; i < wanted.size(); i++) mesh.appendPlaced(meshes.get(i), wanted.get(i).pose);
        placements = List.copyOf(wanted);
        placed = mesh.data();
        return placed;
    }

    private boolean samePlacements(List<Placement> wanted) {
        if (wanted.size() != placements.size()) return false;
        outer:
        for (Placement next : wanted) {
            for (Placement placement : placements) {
                if (Objects.equals(placement.id, next.id)) {
                    if (placement.contentKey != next.contentKey || !AcousticUpdateGate.samePose(placement.pose, next.pose)) return false;
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }
}
