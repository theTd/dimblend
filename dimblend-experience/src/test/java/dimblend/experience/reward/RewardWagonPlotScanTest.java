package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.reward.RewardWagonDissolve.Occupant;
import dimblend.experience.reward.RewardWagonPlotScan.Fill;
import dimblend.experience.reward.RewardWagonPlotScan.Pos;
import dimblend.experience.reward.RewardWagonPlotScan.Result;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading a wagon out of its plot: which blocks keep it alive, and which are the ones the
 * dissolve itself has to reach.
 *
 * <p>The deck here is the requirement's drawing, expressed as fills: two couplers at the ends of
 * the middle column, the slab deck, the bogey. Nothing in this test knows about Minecraft.
 */
class RewardWagonPlotScanTest {
    /** A plot origin, chosen outside the world on purpose: a plot position is not a world one. */
    private static final int PLOT_X = 20_480_000;
    private static final int PLOT_Y = 65;
    private static final int PLOT_Z = 20_480_000;

    private final Map<String, Fill> cells = new HashMap<>();

    private void put(int dx, int dy, int dz, Fill fill) {
        cells.put(key(PLOT_X + dx, PLOT_Y + dy, PLOT_Z + dz), fill);
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** The structure-only deck: couplers at both ends, slabs all over, one bogey. */
    private void putStructureOnly() {
        put(-4, 0, 0, Fill.COUPLER);
        put(4, 0, 0, Fill.COUPLER);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                put(dx, 0, dz, Fill.SLAB);
            }
        }
        put(0, 0, 0, Fill.BOGEY);
    }

    private Result scanDeck() {
        return RewardWagonPlotScan.scan(PLOT_X - 4, PLOT_Y, PLOT_Z - 1,
                PLOT_X + 4, PLOT_Y + 3, PLOT_Z + 1,
                (x, y, z) -> cells.getOrDefault(key(x, y, z), Fill.AIR));
    }

    @Test
    void structureOnlyIsReadAsAnEmptyWagon() {
        putStructureOnly();
        Result result = scanDeck();
        assertTrue(RewardWagonDissolve.isEmpty(result.occupants()),
                "a wagon that only carries its own deck has been emptied");
    }

    @Test
    void oneOreKeepsTheWagonAlive() {
        putStructureOnly();
        put(-1, 1, 0, Fill.CONTENT);
        assertFalse(RewardWagonDissolve.isEmpty(scanDeck().occupants()));
    }

    @Test
    void anEmptiedChestCountsAsTaken() {
        putStructureOnly();
        put(0, 1, 0, Fill.EMPTY_CONTAINER);
        assertTrue(RewardWagonDissolve.isEmpty(scanDeck().occupants()));
    }

    @Test
    void aStockedChestKeepsTheWagonAlive() {
        putStructureOnly();
        put(0, 1, 0, Fill.FILLED_CONTAINER);
        assertFalse(RewardWagonDissolve.isEmpty(scanDeck().occupants()));
    }

    @Test
    void airIsNotAnOccupant() {
        Result result = RewardWagonPlotScan.scan(0, 0, 0, 1, 1, 1, (x, y, z) -> Fill.AIR);
        assertTrue(result.occupants().isEmpty());
        assertTrue(result.couplers().isEmpty());
        assertNull(result.bogey());
    }

    @Test
    void everyCouplerIsReportedAndNoOtherBlockIs() {
        putStructureOnly();
        Result result = scanDeck();
        assertEquals(List.of(new Pos(PLOT_X - 4, PLOT_Y, PLOT_Z), new Pos(PLOT_X + 4, PLOT_Y, PLOT_Z)),
                result.couplers());
    }

    @Test
    void theBogeyIsReportedOnce() {
        putStructureOnly();
        Result result = scanDeck();
        assertEquals(new Pos(PLOT_X, PLOT_Y, PLOT_Z), result.bogey());
    }

    @Test
    void aWagonWithoutABogeyIsStillScanned() {
        put(0, 0, 0, Fill.SLAB);
        Result result = RewardWagonPlotScan.scan(PLOT_X, PLOT_Y, PLOT_Z, PLOT_X, PLOT_Y, PLOT_Z,
                (x, y, z) -> cells.getOrDefault(key(x, y, z), Fill.AIR));
        assertNull(result.bogey(), "the dissolve plays its effects nowhere rather than at a guess");
        assertTrue(RewardWagonDissolve.isEmpty(result.occupants()));
    }

    @Test
    void anUnboundedPlotIsRefused() {
        // A plot that has never had a block written reports an inverted box. Reading that as
        // "nothing is left" would dissolve a wagon that was never read.
        assertNull(RewardWagonPlotScan.scan(10, 10, 10, 9, 10, 10, (x, y, z) -> Fill.SLAB));
        assertNull(RewardWagonPlotScan.scan(10, 10, 10, 10, 9, 10, (x, y, z) -> Fill.SLAB));
        assertNull(RewardWagonPlotScan.scan(10, 10, 10, 10, 10, 9, (x, y, z) -> Fill.SLAB));
    }

    @Test
    void aBoxWiderThanAWagonIsRefused() {
        int span = RewardWagonPlotScan.MAX_SPAN;
        assertNull(RewardWagonPlotScan.scan(0, 0, 0, span, 0, 0, (x, y, z) -> Fill.SLAB));
        assertNull(RewardWagonPlotScan.scan(0, 0, 0, 0, span, 0, (x, y, z) -> Fill.SLAB));
        assertNull(RewardWagonPlotScan.scan(0, 0, 0, 0, 0, span, (x, y, z) -> Fill.SLAB));
    }

    @Test
    void theWagonItselfIsWellInsideTheRefusedSpan() {
        // The deck is 9 x 4 x 3; the guard has to leave it alone.
        assertTrue(RewardWagonLayout.LENGTH < RewardWagonPlotScan.MAX_SPAN);
        assertTrue(RewardWagonLayout.WIDTH < RewardWagonPlotScan.MAX_SPAN);
        assertTrue(RewardWagonLayout.ORE_TOP_DY - RewardWagonLayout.STRUCTURE_DY < RewardWagonPlotScan.MAX_SPAN);
    }

    @Test
    void occupantOfMapsEveryFill() {
        assertEquals(Occupant.STRUCTURE, RewardWagonPlotScan.occupantOf(Fill.SLAB));
        assertEquals(Occupant.STRUCTURE, RewardWagonPlotScan.occupantOf(Fill.COUPLER));
        assertEquals(Occupant.STRUCTURE, RewardWagonPlotScan.occupantOf(Fill.BOGEY));
        assertEquals(Occupant.EMPTY_CONTAINER, RewardWagonPlotScan.occupantOf(Fill.EMPTY_CONTAINER));
        assertEquals(Occupant.FILLED_CONTAINER, RewardWagonPlotScan.occupantOf(Fill.FILLED_CONTAINER));
        assertEquals(Occupant.CONTENT, RewardWagonPlotScan.occupantOf(Fill.CONTENT));
        assertThrows(IllegalArgumentException.class, () -> RewardWagonPlotScan.occupantOf(Fill.AIR));
        assertThrows(IllegalArgumentException.class,
                () -> RewardWagonPlotScan.scan(0, 0, 0, 0, 0, 0, null));
    }
}
