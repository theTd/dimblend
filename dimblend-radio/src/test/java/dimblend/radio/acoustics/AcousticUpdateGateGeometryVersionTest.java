package dimblend.radio.acoustics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticUpdateGateGeometryVersionTest {
    private static Object snapshot(Object terrain, long geometryVersion) {
        AcousticUpdateGate.registerTerrain(terrain, new Long2ObjectOpenHashMap<>(), LongSet.of(1L), geometryVersion);
        Object snapshot = new Object();
        AcousticUpdateGate.registerSnapshot(snapshot, terrain, List.of(), List.of(), List.of());
        return snapshot;
    }

    @Test
    void geometryVersionChangeReArmsTheGateWithIdenticalPalettes() {
        Object owner = new Object();
        Object first = snapshot(new Object(), 100L);
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, first, Vec3.ZERO, Vec3.ZERO, true));
        // Same block palettes, but the render-mesh mirror landed a rebuild: must re-simulate.
        Object rebuilt = snapshot(new Object(), 101L);
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, rebuilt, Vec3.ZERO, Vec3.ZERO, true));
        // Same palettes and same version: settled.
        Object settled = snapshot(new Object(), 101L);
        assertFalse(AcousticUpdateGate.shouldSimulate(owner, settled, Vec3.ZERO, Vec3.ZERO, true));
    }

    @Test
    void legacyCallerWithoutVersionBehavesAsBefore() {
        Object owner = new Object();
        Object terrain = new Object();
        AcousticUpdateGate.registerTerrain(terrain, new Long2ObjectOpenHashMap<>(), LongSet.of(1L));
        Object snapshot = new Object();
        AcousticUpdateGate.registerSnapshot(snapshot, terrain, List.of(), List.of(), List.of());
        assertTrue(AcousticUpdateGate.shouldSimulate(owner, snapshot, Vec3.ZERO, Vec3.ZERO, false));
        Object again = new Object();
        AcousticUpdateGate.registerSnapshot(again, terrain, List.of(), List.of(), List.of());
        assertFalse(AcousticUpdateGate.shouldSimulate(owner, again, Vec3.ZERO, Vec3.ZERO, false));
    }

    @Test
    void geometryChangesBypassTheMotionCadenceWhileIdenticalScenesDoNot() {
        Object owner=new Object();
        Object first=snapshot(new Object(),100L);
        AcousticUpdateGate.shouldSimulate(owner,first,Vec3.ZERO,Vec3.ZERO,false);
        AcousticUpdateGate.shouldSimulate(owner,first,Vec3.ZERO,Vec3.ZERO,true);
        assertFalse(AcousticUpdateGate.geometryChanged(owner,snapshot(new Object(),100L),true));
        Object changed=snapshot(new Object(),101L);
        assertTrue(AcousticUpdateGate.geometryChanged(owner,changed,false));
        assertTrue(AcousticUpdateGate.geometryChanged(owner,changed,true));
    }
}
