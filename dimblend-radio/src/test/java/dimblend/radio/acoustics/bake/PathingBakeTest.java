package dimblend.radio.acoustics.bake;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathingBakeTest {
    private static final BlockPos RADIO = new BlockPos(-20, 64, 33);

    /** Probes relative to the radio's lower corner: one beside it, one 30 blocks east, one deep below. */
    private static PathingBake bake() {
        return new PathingBake(RADIO, new long[0], new long[0], new float[] {2.5f, 1.5f, 0.5f, 30.5f, 1.5f, 0.5f, 0.5f, -40, 0.5f},
                4, new byte[1]);
    }

    @Test
    void probesAreStoredInTheRadiosFrame() {
        var bake = bake();
        assertEquals(3, bake.probeCount());
        assertEquals(new Vec3(-17.5, 65.5, 33.5), bake.probe(0));
        assertEquals(new Vec3(10.5, 65.5, 33.5), bake.probe(1));
        assertThrows(IllegalArgumentException.class,
                () -> new PathingBake(RADIO, new long[0], new long[0], new float[4], 4, new byte[1]));
    }

    @Test
    void probesNearAChangedSectionAreFoundWithinTheMargin() {
        var bake = bake();
        long radioSection = SectionPos.asLong(-2, 4, 2);
        assertEquals(SectionPos.of(RADIO).asLong(), radioSection);
        var near = bake.probesNear(new long[] {radioSection}, 0);
        assertTrue(near.get(0), "inside the radio's section");
        assertFalse(near.get(1), "30 blocks east, two sections over");
        assertFalse(near.get(2), "40 blocks down");
        // The east probe (x 10.5) is 26.5 blocks past the section's east face (x -16).
        assertFalse(bake.probesNear(new long[] {radioSection}, 26).get(1));
        assertTrue(bake.probesNear(new long[] {radioSection}, 27).get(1));
        assertTrue(bake.probesNear(new long[0], 100).isEmpty());
    }
}
