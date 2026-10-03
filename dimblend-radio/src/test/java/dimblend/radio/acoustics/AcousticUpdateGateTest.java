package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticUpdateGateTest {
    @Test
    void equivalentSnapshotObjectsAndTinyJitterDoNotResimulate() {
        Object owner = new Object();
        Object a = scene(LongSet.of(1L)), b = scene(LongSet.of(1L));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, a, Vec3.ZERO, new Vec3(4, 0, 0), true));
        assertFalse(AcousticUpdateGate.shouldSimulate(owner, b, new Vec3(0.001, 0, 0), new Vec3(4.001, 0, 0), true));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, b, Vec3.ZERO, new Vec3(4.1, 0, 0), true));
    }

    @Test
    void geometryAndStructureMotionInvalidateBothKindsOfSimulation() {
        Object owner = new Object(), blocks = new Object();
        AcousticUpdateGate.registerTerrain(blocks, new Long2ObjectOpenHashMap<>(), LongSet.of(1L));
        UUID id = UUID.randomUUID();
        Pose3d pose = new Pose3d();
        Object a = new Object();
        AcousticUpdateGate.registerSnapshot(a, blocks, List.of(blocks), List.of(id), List.of(pose));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, a, Vec3.ZERO, Vec3.ZERO, false));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, a, Vec3.ZERO, Vec3.ZERO, true));
        assertFalse(AcousticUpdateGate.shouldSimulate(owner, a, Vec3.ZERO, Vec3.ZERO, false));
        Pose3d moved = new Pose3d(pose);
        moved.orientation().rotateY(0.1);
        Object b = new Object();
        AcousticUpdateGate.registerSnapshot(b, blocks, List.of(blocks), List.of(id), List.of(moved));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, b, Vec3.ZERO, Vec3.ZERO, true));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, scene(LongSet.of(2L)), Vec3.ZERO, Vec3.ZERO, false));
    }

    /** Motion alone re-simulates on the normal cadence; only content changes may jump the queue. */
    @Test
    void structureMotionIsNotAGeometryChange() {
        Object owner = new Object(), blocks = new Object();
        AcousticUpdateGate.registerTerrain(blocks, new Long2ObjectOpenHashMap<>(), LongSet.of(1L));
        UUID id = UUID.randomUUID();
        Pose3d pose = new Pose3d();
        Object a = new Object();
        AcousticUpdateGate.registerSnapshot(a, blocks, List.of(blocks), List.of(id), List.of(pose));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, a, Vec3.ZERO, Vec3.ZERO, true));
        Pose3d moved = new Pose3d(pose);
        moved.position().set(1, 0, 0);
        Object b = new Object();
        AcousticUpdateGate.registerSnapshot(b, blocks, List.of(blocks), List.of(id), List.of(moved));
        assertFalse(AcousticUpdateGate.geometryChanged(owner, b, true), "a moving train must not bypass the reflection cadence");
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, b, Vec3.ZERO, Vec3.ZERO, true), "but it is still simulated on cadence");
        Object edited = new Object(), other = new Object();
        AcousticUpdateGate.registerTerrain(other, new Long2ObjectOpenHashMap<>(), LongSet.of(2L));
        AcousticUpdateGate.registerSnapshot(edited, blocks, List.of(other), List.of(id), List.of(moved));
        assertTrue(AcousticUpdateGate.geometryChanged(owner, edited, true), "an edited structure is a geometry change");
    }

    /** A terrain edit jumps both queues; an equivalent re-capture of unchanged terrain does not. */
    @Test
    void terrainEditsBypassTheMotionCadenceWhileIdenticalScenesDoNot() {
        Object owner = new Object();
        Object first = scene(LongSet.of(1L));
        AcousticUpdateGate.shouldSimulate(owner, first, Vec3.ZERO, Vec3.ZERO, false);
        AcousticUpdateGate.shouldSimulate(owner, first, Vec3.ZERO, Vec3.ZERO, true);
        assertFalse(AcousticUpdateGate.geometryChanged(owner, scene(LongSet.of(1L)), true));
        Object edited = scene(LongSet.of(2L));
        assertTrue(AcousticUpdateGate.geometryChanged(owner, edited, false));
        assertTrue(AcousticUpdateGate.geometryChanged(owner, edited, true));
    }

    private static Object scene(LongSet chunks) {
        Object terrain = new Object(), scene = new Object();
        AcousticUpdateGate.registerTerrain(terrain, new Long2ObjectOpenHashMap<>(), chunks);
        AcousticUpdateGate.registerSnapshot(scene, terrain, List.of(), List.of(), List.of());
        return scene;
    }
}
