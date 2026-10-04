package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReflectionMeshCacheTest {
    /** Terrain sections default to uncaptured; each terrain/local mesh build is counted. */
    private static final class FakeGeometry implements ReflectionGeometry {
        final Long2LongOpenHashMap sections = new Long2LongOpenHashMap();
        final List<FakeBody> bodies = new ArrayList<>();
        Set<BlockPos> emitters = Set.of();
        AcousticFrame frame = AcousticFrame.WORLD;
        /** Terrain is one triangle at the mesh origin instead of open air. */
        boolean solidTerrain;
        int terrainBuilds;
        AABB built;
        Vec3 builtOrigin;

        FakeGeometry() { sections.defaultReturnValue(UNCAPTURED); }

        @Override public long terrainSection(long key) { return sections.get(key); }
        @Override public int minSection() { return -4; }
        @Override public int maxSection() { return 20; }
        @Override public AcousticMesh.Data terrainMesh(AABB bounds, Vec3 origin, AcousticMesh.Workspace workspace) {
            terrainBuilds++;
            built = bounds;
            builtOrigin = origin;
            return solidTerrain ? new AcousticMesh.Data(new float[] {0, 0, 0, 1, 0, 0, 0, 0, 1}, new int[] {0, 1, 2}, new int[] {0}, origin)
                    : AcousticMesh.Data.empty(origin);
        }
        @Override public List<? extends Body> bodies() { return bodies; }
        @Override public Set<BlockPos> emitters() { return emitters; }
        @Override public AcousticFrame frame() { return frame; }
    }

    /** One triangle at the structure's local origin. */
    private static final class FakeBody implements ReflectionGeometry.Body {
        final UUID id = UUID.randomUUID();
        final Pose3d pose = new Pose3d();
        long contentKey = 1;
        int localBuilds;

        @Override public UUID id() { return id; }
        @Override public long contentKey() { return contentKey; }
        @Override public Pose3dc pose() { return pose; }
        @Override public AcousticMesh.Data localMesh(AcousticMesh.Workspace workspace) {
            localBuilds++;
            return new AcousticMesh.Data(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2}, new int[] {3}, new Vec3(2, 0, 0));
        }
    }

    private static final Vec3 SOURCE = new Vec3(4, 0, 0);

    @Test void cameraMotionReusesTerrainUntilItLeavesThePaddedRegion() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        var first = cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        for (int i = 0; i < 50; i++) assertSame(first, cache.get(geometry, new Vec3(0, 0, i * 0.1), List.of(SOURCE)));
        assertEquals(1, geometry.terrainBuilds, "Movement must not remesh unchanged terrain each simulation");
        assertSame(first.fixed(), cache.get(geometry, new Vec3(0, 0, ReflectionMeshCache.PADDING - 1), List.of(SOURCE)).fixed());
        assertNotSame(first.fixed(), cache.get(geometry, new Vec3(0, 0, ReflectionMeshCache.PADDING + 1), List.of(SOURCE)).fixed());
        assertEquals(2, geometry.terrainBuilds);
    }

    @Test void rebuiltRegionIsTheOneSnapshotsAreSizedFor() {
        var geometry = new FakeGeometry();
        new ReflectionMeshCache().get(geometry, Vec3.ZERO, List.of(new Vec3(4, 2, 0)));
        assertEquals(ReflectionMeshCache.region(Vec3.ZERO, new Vec3(4, 2, 0)), geometry.built);
    }

    /** Several radios share one scene: its region spans them all, and their cells are open in it. */
    @Test void oneSceneSpansEveryRadioAndRemeshesWhenTheRadiosChange() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        Vec3 far = new Vec3(-40, 0, 6);
        cache.get(geometry, Vec3.ZERO, List.of(SOURCE, far));
        assertEquals(ReflectionMeshCache.region(Vec3.ZERO, SOURCE).minmax(ReflectionMeshCache.region(Vec3.ZERO, far)), geometry.built,
                "one region covers every radio simulated with the listener");
        cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        cache.get(geometry, Vec3.ZERO, List.of(far, SOURCE));
        assertEquals(1, geometry.terrainBuilds, "radios inside the meshed region reuse it, whichever of them run");
        geometry.emitters = Set.of(BlockPos.containing(SOURCE));
        cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        assertEquals(2, geometry.terrainBuilds, "a radio's own block is open in the mesh, so a different set of radios remeshes");
        cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        assertEquals(2, geometry.terrainBuilds);
    }

    @Test void aMovingTrainIsPlacedAgainWithoutRemeshingTerrainOrItself() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        var train = new FakeBody();
        geometry.bodies.add(train);
        var first = cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        Vec3 origin = first.fixed().origin();
        assertEquals(1, first.placed().triangleCount());
        assertEquals(2 - origin.x, first.placed().vertices()[0], 1e-6);
        var previous = first;
        for (int step = 1; step <= 20; step++) {
            train.pose.position().set(step * 0.5, 0, 0);
            var scene = cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
            assertSame(previous.fixed(), scene.fixed());
            assertNotSame(previous.placed(), scene.placed());
            assertEquals(2 + step * 0.5 - origin.x, scene.placed().vertices()[0], 1e-5, "vertices follow the pose");
            previous = scene;
        }
        assertEquals(1, geometry.terrainBuilds);
        assertEquals(1, train.localBuilds, "the train is voxelized once while only its pose changes");
        assertSame(previous, cache.get(geometry, Vec3.ZERO, List.of(SOURCE)), "a parked train reuses the whole scene");
        train.contentKey = 2;
        cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        assertEquals(2, train.localBuilds, "an edited train is voxelized again");
        assertEquals(1, geometry.terrainBuilds);
    }

    @Test void slowStructureDriftIsPlacedOnceItAddsUpPastThePoseTolerance() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        var train = new FakeBody();
        geometry.bodies.add(train);
        var placements = new java.util.HashSet<AcousticMesh.Data>();
        for (int i = 0; i <= 10; i++) {
            // Each step is below the 0.01-block pose tolerance; the total is five times above it.
            train.pose.position().set(i * 0.005, 0, 0);
            placements.add(cache.get(geometry, Vec3.ZERO, List.of(SOURCE)).placed());
        }
        assertTrue(placements.size() >= 3, "A moving structure must not drift away from its uploaded mesh, placements=" + placements.size());
    }

    @Test void sectionEditsInsideTheMeshRegionRebuildTerrainAndOthersDoNot() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        long inside = SectionPos.asLong(0, 0, 0), outside = SectionPos.asLong(40, 0, 0);
        geometry.sections.put(inside, 7);
        var first = cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        geometry.sections.put(outside, 9);
        assertSame(first, cache.get(geometry, Vec3.ZERO, List.of(SOURCE)), "edits beyond the mesh region are irrelevant");
        geometry.sections.remove(inside);
        assertSame(first, cache.get(geometry, Vec3.ZERO, List.of(SOURCE)), "a section this snapshot did not capture is not a change");
        geometry.sections.put(inside, 8);
        assertNotSame(first.fixed(), cache.get(geometry, Vec3.ZERO, List.of(SOURCE)).fixed(), "a changed section inside is");
        assertEquals(2, geometry.terrainBuilds);
        long neighbour = SectionPos.asLong(1, 0, 0);
        geometry.sections.put(neighbour, ReflectionGeometry.AIR);
        cache.get(geometry, Vec3.ZERO, List.of(SOURCE));
        assertEquals(3, geometry.terrainBuilds, "a section first captured after the build is meshed");
    }

    /** The scene in {@code train}'s frame where the train is now, heard from {@code seat} on board. */
    private static ReflectionMeshCache.Scene ride(ReflectionMeshCache cache, FakeGeometry geometry, FakeBody train, Vec3 seat) {
        geometry.frame = AcousticFrame.of(train.id, train.pose);
        Vec3 listener = geometry.frame.toWorld(seat);
        return cache.get(geometry, listener, List.of(geometry.frame.toWorld(seat.add(3, 0, 0))));
    }

    /**
     * Riding a train, the train is the still part of the scene and the terrain is what moves: the
     * train's mesh is never placed again however far it goes, and a parked train reuses everything.
     */
    @Test void ridingATrainKeepsItStillAndMovesTheTerrainThroughIt() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        geometry.solidTerrain = true;
        var train = new FakeBody();
        geometry.bodies.add(train);
        Vec3 seat = new Vec3(2, 1, 0);
        ReflectionMeshCache.Scene previous = null;
        for (int step = 0; step <= 10; step++) {
            train.pose.position().set(step * 0.5, 0, 0);
            var scene = ride(cache, geometry, train, seat);
            assertEquals(new Vec3(2, 0, 0), scene.fixed().origin(), "the scene is in the train's frame, at its mesh");
            assertEquals(0, scene.fixed().vertices()[0], 1e-6, "the train's own mesh, unmoved");
            assertEquals(1, scene.placed().triangleCount(), "the terrain, and not the train again");
            assertEquals(geometry.builtOrigin.x - step * 0.5 - 2, scene.placed().vertices()[0], 1e-5,
                    "the terrain is where the train sees it");
            if (previous != null) {
                assertSame(previous.fixed(), scene.fixed());
                assertNotSame(previous.placed(), scene.placed());
            }
            previous = scene;
        }
        assertEquals(1, geometry.terrainBuilds);
        assertEquals(1, train.localBuilds);
        assertSame(previous, ride(cache, geometry, train, seat), "a parked train reuses the whole scene");
    }

    /** Coupled carriages keep their place in each other's frame while the train runs. */
    @Test void aCarriageMovingWithTheTrainStaysPutInItsFrame() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        var train = new FakeBody();
        var carriage = new FakeBody();
        geometry.bodies.add(train);
        geometry.bodies.add(carriage);
        float placedAt = Float.NaN;
        for (int step = 0; step <= 10; step++) {
            train.pose.position().set(step * 0.5, 0, 0);
            carriage.pose.position().set(step * 0.5 - 12, 0, 0);
            var scene = ride(cache, geometry, train, new Vec3(2, 1, 0));
            assertEquals(1, scene.placed().triangleCount(), "open-air terrain and the carriage");
            if (Float.isNaN(placedAt)) placedAt = scene.placed().vertices()[0];
            assertEquals(placedAt, scene.placed().vertices()[0], 1e-5f);
        }
        assertEquals(-12, placedAt, 1e-5f);
        assertEquals(1, carriage.localBuilds);
    }

    @Test void gettingOffPutsTheTerrainBackAsTheStillPart() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        geometry.solidTerrain = true;
        var train = new FakeBody();
        geometry.bodies.add(train);
        train.pose.position().set(3, 0, 0);
        var riding = ride(cache, geometry, train, new Vec3(2, 1, 0));
        geometry.frame = AcousticFrame.WORLD;
        var off = cache.get(geometry, new Vec3(5, 1, 0), List.of(new Vec3(8, 1, 0)));
        assertNotSame(riding.fixed(), off.fixed());
        assertEquals(geometry.builtOrigin, off.fixed().origin());
        assertEquals(1, off.fixed().triangleCount());
        assertEquals(2 + 3 - geometry.builtOrigin.x, off.placed().vertices()[0], 1e-5, "the train is placed again");
        assertEquals(1, geometry.terrainBuilds, "the terrain's mesh outlives the frame switch");
        assertEquals(1, train.localBuilds);
        var back = ride(cache, geometry, train, new Vec3(2, 1, 0));
        assertSame(riding.fixed(), back.fixed());
    }

    /** A frame whose structure is missing from the snapshot (it has no surfaces in range) still has a near origin. */
    @Test void aFrameWithoutItsStructureStillHasANearbyOrigin() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        geometry.solidTerrain = true;
        Pose3d pose = new Pose3d(new org.joml.Vector3d(100, 64, 100), new org.joml.Quaterniond(),
                new org.joml.Vector3d(20_480_008, 64, 20_480_008), new org.joml.Vector3d(1));
        geometry.frame = AcousticFrame.of(UUID.randomUUID(), pose);
        var scene = cache.get(geometry, new Vec3(101, 65, 101), List.of(new Vec3(104, 65, 101)));
        assertEquals(0, scene.fixed().triangleCount());
        assertEquals(new Vec3(20_480_000, 64, 20_480_000), scene.fixed().origin());
        assertEquals(1, scene.placed().triangleCount(), "the terrain");
    }
}
