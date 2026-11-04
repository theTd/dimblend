package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R6 depot hall: the terrain is cut open, not sealed.
 *
 * <p>The sealed shell existed because the hall is wider than the corridor tunnel and water or
 * lava in the surrounding terrain could flow in. The user removed it on 2026-11-03 ("cut the
 * terrain open, no need to seal the edges"), so the worldgen pass now writes exactly the carved
 * volume and the shell geometry is gone. This test pins that removal so a later edit cannot put
 * a wall or a roof back by accident.
 */
class StationDepotShellTest {
    private static final int BAND = BandIndex.DEFAULT_BAND_SIZE;
    private static final int B = 2048;

    @Test
    void theWriteSpanIsExactlyTheCarvedHall() {
        assertEquals(StationDepotGeometry.minBlockX(B), StationDepotGeometry.writeMinBlockX(B),
                "no shell column west of the hall");
        assertEquals(StationDepotGeometry.maxBlockX(B), StationDepotGeometry.writeMaxBlockX(B),
                "no shell column east of the hall");
        assertEquals(-StationDepotGeometry.PLATFORM_HALF_WIDTH, StationDepotGeometry.writeMinBlockZ(),
                "no shell column on the negative Z side");
        assertEquals(StationDepotGeometry.PLATFORM_HALF_WIDTH, StationDepotGeometry.writeMaxBlockZ(),
                "no shell column on the positive Z side");
    }

    @Test
    void theHallStillFitsBetweenTwoBoundaryColumns() {
        assertTrue(StationDepotGeometry.writeMinBlockX(B) > B - BAND, "does not reach the previous boundary");
        assertTrue(StationDepotGeometry.writeMaxBlockX(B) < B + BAND, "does not reach the next boundary");
    }

    @Test
    void theCarvedHallReachesTheTunnelCeiling() {
        assertEquals(OakTrackCorridor.VAULT_APEX_DY, StationDepotGeometry.CAVERN_APEX_DY,
                "the chamber is cut up to the corridor vault apex, not stopped below it");
        assertTrue(StationDepotGeometry.carves(B, B, OakTrackCorridor.VAULT_APEX_DY, 0),
                "the apex row is inside the carve");
        assertTrue(!StationDepotGeometry.carves(B, B, OakTrackCorridor.VAULT_APEX_DY + 1, 0),
                "one row above the apex is outside the carve");
    }

    @Test
    void everyWrittenCellIsInsideTheCarveOrItsDeck() {
        for (var cell : StationDepotGeometry.placedCells(B)) {
            int dx = cell.x() - B;
            assertTrue(dx >= StationDepotGeometry.CAVERN_MIN_DX && dx <= StationDepotGeometry.CAVERN_MAX_DX,
                    "cell outside the hall in X: " + cell);
            assertTrue(Math.abs(cell.z()) <= StationDepotGeometry.PLATFORM_HALF_WIDTH,
                    "cell outside the hall in Z: " + cell);
        }
    }
}