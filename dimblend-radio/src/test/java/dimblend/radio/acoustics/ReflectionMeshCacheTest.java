package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        long renderVersion;
        int terrainBuilds;
        AABB built;

        FakeGeometry() { sections.defaultReturnValue(UNCAPTURED); }

        @Override public long terrainSection(long key) { return sections.get(key); }
        @Override public int minSection() { return -4; }
        @Override public int maxSection() { return 20; }
        @Override public long renderGeometryVersion(AABB bounds) { return renderVersion; }
        @Override public AcousticMesh.Data terrainMesh(AABB bounds, Vec3 origin, AcousticMesh.Workspace workspace) {
            terrainBuilds++;
            built = bounds;
            return AcousticMesh.Data.empty(origin);
        }
        @Override public List<? extends Body> bodies() { return bodies; }
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
        var first = cache.get(geometry, Vec3.ZERO, SOURCE);
        for (int i = 0; i < 50; i++) assertSame(first, cache.get(geometry, new Vec3(0, 0, i * 0.1), SOURCE));
        assertEquals(1, geometry.terrainBuilds, "Movement must not remesh unchanged terrain each simulation");
        assertSame(first.terrain(), cache.get(geometry, new Vec3(0, 0, ReflectionMeshCache.PADDING - 1), SOURCE).terrain());
        assertNotSame(first.terrain(), cache.get(geometry, new Vec3(0, 0, ReflectionMeshCache.PADDING + 1), SOURCE).terrain());
        assertEquals(2, geometry.terrainBuilds);
    }

    @Test void rebuiltRegionIsTheOneSnapshotsAreSizedFor() {
        var geometry = new FakeGeometry();
        new ReflectionMeshCache().get(geometry, Vec3.ZERO, new Vec3(4, 2, 0));
        assertEquals(ReflectionMeshCache.region(Vec3.ZERO, new Vec3(4, 2, 0)), geometry.built);
    }

    @Test void aMovingTrainIsPlacedAgainWithoutRemeshingTerrainOrItself() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        var train = new FakeBody();
        geometry.bodies.add(train);
        var first = cache.get(geometry, Vec3.ZERO, SOURCE);
        Vec3 origin = first.terrain().origin();
        assertEquals(1, first.structures().triangleCount());
        assertEquals(2 - origin.x, first.structures().vertices()[0], 1e-6);
        var previous = first;
        for (int step = 1; step <= 20; step++) {
            train.pose.position().set(step * 0.5, 0, 0);
            var scene = cache.get(geometry, Vec3.ZERO, SOURCE);
            assertSame(previous.terrain(), scene.terrain());
            assertNotSame(previous.structures(), scene.structures());
            assertEquals(2 + step * 0.5 - origin.x, scene.structures().vertices()[0], 1e-5, "vertices follow the pose");
            previous = scene;
        }
        assertEquals(1, geometry.terrainBuilds);
        assertEquals(1, train.localBuilds, "the train is voxelized once while only its pose changes");
        assertSame(previous, cache.get(geometry, Vec3.ZERO, SOURCE), "a parked train reuses the whole scene");
        train.contentKey = 2;
        cache.get(geometry, Vec3.ZERO, SOURCE);
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
            placements.add(cache.get(geometry, Vec3.ZERO, SOURCE).structures());
        }
        assertTrue(placements.size() >= 3, "A moving structure must not drift away from its uploaded mesh, placements=" + placements.size());
    }

    @Test void sectionEditsInsideTheMeshRegionRebuildTerrainAndOthersDoNot() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        long inside = SectionPos.asLong(0, 0, 0), outside = SectionPos.asLong(40, 0, 0);
        geometry.sections.put(inside, 7);
        var first = cache.get(geometry, Vec3.ZERO, SOURCE);
        geometry.sections.put(outside, 9);
        assertSame(first, cache.get(geometry, Vec3.ZERO, SOURCE), "edits beyond the mesh region are irrelevant");
        geometry.sections.remove(inside);
        assertSame(first, cache.get(geometry, Vec3.ZERO, SOURCE), "a section this snapshot did not capture is not a change");
        geometry.sections.put(inside, 8);
        assertNotSame(first.terrain(), cache.get(geometry, Vec3.ZERO, SOURCE).terrain(), "a changed section inside is");
        assertEquals(2, geometry.terrainBuilds);
        long neighbour = SectionPos.asLong(1, 0, 0);
        geometry.sections.put(neighbour, ReflectionGeometry.AIR);
        cache.get(geometry, Vec3.ZERO, SOURCE);
        assertEquals(3, geometry.terrainBuilds, "a section first captured after the build is meshed");
    }

    @Test void renderGeometryAndModeChangesRebuildTerrainWhileStationary() {
        var cache = new ReflectionMeshCache();
        var geometry = new FakeGeometry();
        var first = cache.get(geometry, Vec3.ZERO, Vec3.ZERO);
        geometry.renderVersion = 2;
        var rebuilt = cache.get(geometry, Vec3.ZERO, Vec3.ZERO);
        assertNotSame(first.terrain(), rebuilt.terrain());
        var previous = TerrainGeometryMode.CURRENT;
        try {
            TerrainGeometryMode.CURRENT = previous == TerrainGeometryMode.VOXEL ? TerrainGeometryMode.AUTO : TerrainGeometryMode.VOXEL;
            assertNotSame(rebuilt.terrain(), cache.get(geometry, Vec3.ZERO, Vec3.ZERO).terrain());
        } finally { TerrainGeometryMode.CURRENT = previous; }
    }
}
