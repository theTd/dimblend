package dimblend.radio.acoustics;

import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        assertNotSame(first,cache.get(scene(1),new Vec3(0,0,12),new Vec3(4,0,0),build));
        assertEquals(2,builds.get());
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
