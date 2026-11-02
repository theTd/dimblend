package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.Config;
import dimblend.experience.tuning.TuningOption;
import org.junit.jupiter.api.Test;

/**
 * R6 gate: a surface station hands out its free carriage only while the save holds fewer
 * than {@code stationCarriageLimit} force-loaded frame-carrying Sable structures.
 *
 * <p>The counting itself needs a running server, so what is pinned here is the decision and
 * the identity of a "car": those are the two halves a later change can silently flip.
 */
class StationDepotCarriageBudgetTest {

    @Test
    void theGateOpensBelowTheLimitAndClosesAtIt() {
        for (int limit = 3; limit <= 11; limit++) {
            assertTrue(StationDepotCarriageBudget.admits(0, limit), "empty save, limit " + limit);
            assertTrue(StationDepotCarriageBudget.admits(limit - 1, limit), "one short, limit " + limit);
            assertFalse(StationDepotCarriageBudget.admits(limit, limit), "exactly at limit " + limit);
            assertFalse(StationDepotCarriageBudget.admits(limit + 1, limit), "over, limit " + limit);
        }
    }

    /** Two cars past a limit of six stay blocked; the gate is a ceiling, not a countdown. */
    @Test
    void exceedingTheLimitNeverReopensTheGate() {
        assertFalse(StationDepotCarriageBudget.admits(7, 6));
        assertFalse(StationDepotCarriageBudget.admits(64, 6));
    }

    /**
     * 「车架方块」 is the copycat block a chassis deck is built from. A contraption made of
     * shafts, bogeys or couplers alone is not a car and must not spend the budget.
     */
    @Test
    void onlyChassisFrameBlocksMarkACar() {
        assertTrue(StationDepotCarriageBudget.holdsFrameBlock("dimblend_blocks:creative_copycat_panel"));
        assertTrue(StationDepotCarriageBudget.holdsFrameBlock("dimblend_blocks:creative_copycat_slab"));
        assertTrue(StationDepotCarriageBudget.holdsFrameBlock("dimblend_blocks:creative_clean_copycat_slab"));
        // Running gear on its own is not a car.
        assertFalse(StationDepotCarriageBudget.holdsFrameBlock("simurail:physics_bogey"));
        assertFalse(StationDepotCarriageBudget.holdsFrameBlock("simurail:automatic_coupler"));
        assertFalse(StationDepotCarriageBudget.holdsFrameBlock("create:shaft"));
        assertFalse(StationDepotCarriageBudget.holdsFrameBlock("minecraft:stone"));
        assertFalse(StationDepotCarriageBudget.holdsFrameBlock(null));
    }

    /** The panel option, its config and its documented range must not drift apart. */
    @Test
    void thePanelOptionMirrorsTheConfigAndItsRange() {
        var option = TuningOption.STATION_CARRIAGE_LIMIT;
        assertFalse(option.isCheckbox());
        assertEquals("station_carriage_limit", option.id());
        assertEquals((double) Config.STATION_CARRIAGE_LIMIT.getDefault(), option.defaultValue());
        assertEquals(6.0D, option.defaultValue());
        assertEquals(3.0D, option.minimum());
        assertEquals(11.0D, option.maximum());
        assertEquals(1.0D, option.step());
        for (double valid : new double[] {3.0D, 4.0D, 6.0D, 10.0D, 11.0D}) {
            assertTrue(option.isValid(valid), "valid " + valid);
        }
        for (double invalid : new double[] {2.0D, 0.0D, 11.5D, 12.0D, -1.0D}) {
            assertFalse(option.isValid(invalid), "invalid " + invalid);
        }
        assertEquals("6", option.format(option.defaultValue()));
        // experience owns it: it must never be routed into sable's physics config.
        assertFalse(option.sable());
    }
}