package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticUpdateGateTest {
    @Test
    void equivalentSnapshotObjectsAndTinyJitterDoNotResimulate() {
        Object owner = new Object();
        Object a = scene(Set.of(1L)), b = scene(Set.of(1L));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, a, Vec3.ZERO, new Vec3(4, 0, 0), true));
        assertFalse(AcousticUpdateGate.shouldSimulate(owner, b, new Vec3(0.001, 0, 0), new Vec3(4.001, 0, 0), true));
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, b, Vec3.ZERO, new Vec3(4.1, 0, 0), true));
    }

    @Test
    void geometryAndStructureMotionInvalidateBothKindsOfSimulation() {
        Object owner = new Object(), blocks = new Object();
        AcousticUpdateGate.registerTerrain(blocks, Map.of(), Set.of(1L));
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
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, scene(Set.of(2L)), Vec3.ZERO, Vec3.ZERO, false));
    }

    private static Object scene(Set<Long> chunks) {
        Object terrain = new Object(), scene = new Object();
        AcousticUpdateGate.registerTerrain(terrain, Map.of(), chunks);
        AcousticUpdateGate.registerSnapshot(scene, terrain, List.of(), List.of(), List.of());
        return scene;
    }
}
