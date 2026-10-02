package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReflectionMeshCacheTest {
    private Object scene(long revision) {
        Object terrain = new Object(), snapshot = new Object();
        AcousticUpdateGate.registerTerrain(terrain,Map.of(),Set.of(1L),revision);
        AcousticUpdateGate.registerSnapshot(snapshot,terrain,List.of(),List.of(),List.of());
        return snapshot;
    }

    @Test void cameraMotionReusesMeshUntilItLeavesThePaddedRegion() {
        var cache = new ReflectionMeshCache();
        var builds = new AtomicInteger();
        Function<AABB,AcousticMesh.Data> build = bounds -> {
            builds.incrementAndGet();
            return new AcousticMesh.Data(new float[0],new int[0],new int[0],bounds.getCenter());
        };
        var first = cache.get(scene(1),Vec3.ZERO,new Vec3(4,0,0),build);
        for(int i=0;i<50;i++) {
            assertSame(first,cache.get(scene(1),new Vec3(0,0,i*0.1),new Vec3(4,0,0),build));
        }
        assertEquals(1,builds.get(),"Movement must not remesh unchanged terrain each simulation");
        assertSame(first,cache.get(scene(1),new Vec3(0,0,ReflectionMeshCache.PADDING-1),new Vec3(4,0,0),build));
        assertNotSame(first,cache.get(scene(1),new Vec3(0,0,ReflectionMeshCache.PADDING+1),new Vec3(4,0,0),build));
        assertEquals(2,builds.get());
    }

    @Test void rebuiltRegionIsTheOneSnapshotsAreSizedFor() {
        var cache = new ReflectionMeshCache();
        var built = new AABB[1];
        cache.get(scene(1),Vec3.ZERO,new Vec3(4,2,0),bounds -> {
            built[0] = bounds;
            return new AcousticMesh.Data(new float[0],new int[0],new int[0],Vec3.ZERO);
        });
        assertEquals(ReflectionMeshCache.region(Vec3.ZERO,new Vec3(4,2,0)),built[0]);
    }

    @Test void slowStructureDriftRebuildsOnceItAddsUpPastThePoseTolerance() {
        var cache = new ReflectionMeshCache();
        var builds = new AtomicInteger();
        Function<AABB,AcousticMesh.Data> build = bounds -> {
            builds.incrementAndGet();
            return new AcousticMesh.Data(new float[0],new int[0],new int[0],Vec3.ZERO);
        };
        Object terrain = new Object();
        AcousticUpdateGate.registerTerrain(terrain,Map.of(),Set.of(1L),1);
        UUID id = UUID.randomUUID();
        for (int i = 0; i <= 10; i++) {
            // Each step is below the 0.01-block pose tolerance; the total is five times above it.
            Pose3d pose = new Pose3d();
            pose.position().set(i * 0.005, 0, 0);
            Object snapshot = new Object();
            AcousticUpdateGate.registerSnapshot(snapshot,terrain,List.of(terrain),List.of(id),List.of(pose));
            cache.get(snapshot,Vec3.ZERO,new Vec3(4,0,0),build);
        }
        assertTrue(builds.get() >= 3, "A moving structure must not drift away from its uploaded mesh, builds=" + builds.get());
    }

    @Test void blockAndRenderGeometryChangesInvalidateTheCacheEvenWhileStationary() {
        var cache=new ReflectionMeshCache();
        Function<AABB,AcousticMesh.Data> build=bounds->new AcousticMesh.Data(new float[0],new int[0],new int[0],Vec3.ZERO);
        var first=cache.get(scene(1),Vec3.ZERO,Vec3.ZERO,build);
        assertNotSame(first,cache.get(scene(2),Vec3.ZERO,Vec3.ZERO,build));
        var previous=TerrainGeometryMode.CURRENT;
        try {
            var before=cache.get(scene(2),Vec3.ZERO,Vec3.ZERO,build);
            TerrainGeometryMode.CURRENT=previous==TerrainGeometryMode.VOXEL?TerrainGeometryMode.AUTO:TerrainGeometryMode.VOXEL;
            assertNotSame(before,cache.get(scene(2),Vec3.ZERO,Vec3.ZERO,build));
        } finally { TerrainGeometryMode.CURRENT=previous; }
    }
}
