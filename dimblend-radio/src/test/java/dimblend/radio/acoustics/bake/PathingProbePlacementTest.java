package dimblend.radio.acoustics.bake;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathingProbePlacementTest {
    private static final BlockPos RADIO = new BlockPos(0, 0, 0);

    /** Solid ground below y = 0, the radio block at the origin, open air above. */
    private static byte flat(int x, int y, int z) {
        if (x == 0 && y == 0 && z == 0) return PathingProbePlacement.SOLID;
        return y < 0 ? PathingProbePlacement.SOLID : PathingProbePlacement.OPEN;
    }

    @Test
    void flatGroundGetsOneProbePerColumnCellAtHeadHeight() {
        var probes = PathingProbePlacement.place(PathingProbePlacementTest::flat, RADIO, 16, 1000);
        assertTrue(probes.complete());
        assertEquals(4, probes.cellSize());
        assertEquals(4, probes.radius());
        Set<Long> columns = new HashSet<>();
        for (int i = 0; i < probes.count(); i++) {
            double x = probes.centres()[i * 3], y = probes.centres()[i * 3 + 1], z = probes.centres()[i * 3 + 2];
            assertEquals(1.5, y, 1e-9, "head height above the ground");
            long column = ((long) Math.floorDiv((int) Math.floor(x), 4) << 32) | (Math.floorDiv((int) Math.floor(z), 4) & 0xffffffffL);
            assertTrue(columns.add(column), "one probe per column cell");
            assertFalse(x == 0.5 && z == 0.5, "not inside the radio block");
        }
        // A disc of radius 16 holds about pi * 16^2 / 16 = 50 column cells of 4 x 4.
        assertTrue(probes.count() >= 40 && probes.count() <= 70, "probe count " + probes.count());
    }

    /** A floor slab at y = 5 over the flat ground; a 2 x 2 stairwell hole at x, z in [6, 7] when open. */
    private static PathingProbePlacement.Cells storeys(boolean stairwell) {
        return (x, y, z) -> {
            if (y == 5) {
                boolean hole = stairwell && x >= 6 && x <= 7 && z >= 6 && z <= 7;
                return hole ? PathingProbePlacement.OPEN : PathingProbePlacement.SOLID;
            }
            return flat(x, y, z);
        };
    }

    @Test
    void anUpperFloorCountsOnlyWhenTheAirReachesIt() {
        var sealed = PathingProbePlacement.place(storeys(false), RADIO, 16, 1000);
        var open = PathingProbePlacement.place(storeys(true), RADIO, 16, 1000);
        assertEquals(0, upper(sealed), "no probes above a slab the sound cannot pass");
        assertTrue(upper(open) > 10, "the upper floor through the stairwell: " + upper(open));
        assertTrue(open.count() > sealed.count());
    }

    private static int upper(PathingProbePlacement.Probes probes) {
        int count = 0;
        for (int i = 0; i < probes.count(); i++) if (probes.centres()[i * 3 + 1] == 6 + 1.5) count++;
        return count;
    }

    @Test
    void aSealedCaveBelowGetsNoProbes() {
        PathingProbePlacement.Cells cave = (x, y, z) -> y >= -10 && y <= -8 && Math.abs(x) < 6 && Math.abs(z) < 6
                ? PathingProbePlacement.OPEN : flat(x, y, z);
        var probes = PathingProbePlacement.place(cave, RADIO, 16, 1000);
        for (int i = 0; i < probes.count(); i++) assertTrue(probes.centres()[i * 3 + 1] > 0, "only the surface");
    }

    @Test
    void reachingAnUnloadedChunkMarksTheRegionIncomplete() {
        PathingProbePlacement.Cells partial = (x, y, z) -> x >= 10 ? PathingProbePlacement.UNKNOWN : flat(x, y, z);
        assertFalse(PathingProbePlacement.place(partial, RADIO, 16, 1000).complete());
    }

    @Test
    void tooManyProbesWidenTheColumnCells() {
        var probes = PathingProbePlacement.place(PathingProbePlacementTest::flat, RADIO, 48, 120);
        assertTrue(probes.count() <= 120, "within budget: " + probes.count());
        assertTrue(probes.cellSize() > 4, "wider cells: " + probes.cellSize());
        assertEquals(probes.cellSize(), probes.radius());
    }

    @Test
    void aWallSplitsColumnLevelsOnlyByHeight() {
        // Ground steps up by one block at x >= 3: still one floor level per column cell.
        PathingProbePlacement.Cells step = (x, y, z) -> x >= 3 && y == 0 ? PathingProbePlacement.SOLID : flat(x, y, z);
        var probes = PathingProbePlacement.place(step, RADIO, 12, 1000);
        Set<Long> columns = new HashSet<>();
        for (int i = 0; i < probes.count(); i++) {
            int x = (int) Math.floor(probes.centres()[i * 3]), z = (int) Math.floor(probes.centres()[i * 3 + 2]);
            assertTrue(columns.add(((long) Math.floorDiv(x, 4) << 32) | (Math.floorDiv(z, 4) & 0xffffffffL)),
                    "a one-block step stays one level");
        }
    }
}
