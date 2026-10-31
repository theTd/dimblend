package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** R6 depot hall shell: the cave is wider than the corridor tunnel, so it must seal itself. */
class StationDepotShellTest {
    private static final int BAND = BandIndex.DEFAULT_BAND_SIZE;
    private static final int B = 2048;
    private static final IntFunction<String> LANES = region -> Math.abs(region) <= 1 ? "surface" : "underground";

    @Test
    void theShellIsTheRingJustOutsideTheCarvedHall() {
        // Sides.
        assertTrue(StationDepotGeometry.isShell(B, B + StationDepotGeometry.CAVERN_MIN_DX - 1, 0, 0));
        assertTrue(StationDepotGeometry.isShell(B, B + StationDepotGeometry.CAVERN_MAX_DX + 1, 0, 0));
        assertTrue(StationDepotGeometry.isShell(B, B, 0, StationDepotGeometry.PLATFORM_HALF_WIDTH + 1));
        assertTrue(StationDepotGeometry.isShell(B, B, 0, -StationDepotGeometry.PLATFORM_HALF_WIDTH - 1));
        // Ceiling and footing.
        assertTrue(StationDepotGeometry.isShell(B, B, StationDepotGeometry.CAVERN_APEX_DY + 1, 0));
        assertTrue(StationDepotGeometry.isShell(B, B, StationDepotGeometry.DECK_DY - 1, 0));
    }

    @Test
    void theShellNeverLandsInsideTheHall() {
        for (var cell : StationDepotGeometry.placedCells(B)) {
            assertFalse(StationDepotGeometry.isShell(B, cell.x(), cell.dy(), cell.z()),
                    "a shell block would overwrite depot content: " + cell);
        }
    }

    @Test
    void theShellStopsTwoBlocksAwayFromTheHall() {
        assertFalse(StationDepotGeometry.isShell(B, B + StationDepotGeometry.CAVERN_MIN_DX - 2, 0, 0));
        assertFalse(StationDepotGeometry.isShell(B, B + StationDepotGeometry.CAVERN_MAX_DX + 2, 0, 0));
        assertFalse(StationDepotGeometry.isShell(B, B, 0, StationDepotGeometry.PLATFORM_HALF_WIDTH + 2));
        assertFalse(StationDepotGeometry.isShell(B, B, StationDepotGeometry.CAVERN_APEX_DY + 2, 0));
        assertFalse(StationDepotGeometry.isShell(B, B, StationDepotGeometry.DECK_DY - 2, 0));
    }

    @Test
    void theShellAndTheHallTogetherFitBetweenTwoBoundaryColumns() {
        assertTrue(StationDepotGeometry.writeMinBlockX(B) > B - BAND, "does not reach the previous boundary");
        assertTrue(StationDepotGeometry.writeMaxBlockX(B) < B + BAND, "does not reach the next boundary");
        // The write span is the hall plus exactly one shell column on each end.
        assertEquals(StationDepotGeometry.minBlockX(B) - 1, StationDepotGeometry.writeMinBlockX(B));
        assertEquals(StationDepotGeometry.maxBlockX(B) + 1, StationDepotGeometry.writeMaxBlockX(B));
    }

    @Test
    void theShellEntirelyCoversTheCeilingSoNoFluidLeaksFromAbove() {
        for (int dx = StationDepotGeometry.CAVERN_MIN_DX; dx <= StationDepotGeometry.CAVERN_MAX_DX; dx++) {
            for (int z = -StationDepotGeometry.PLATFORM_HALF_WIDTH;
                    z <= StationDepotGeometry.PLATFORM_HALF_WIDTH; z++) {
                assertTrue(StationDepotGeometry.isShell(B, B + dx, StationDepotGeometry.CAVERN_APEX_DY + 1, z),
                        "no gap in the ceiling at dx=" + dx + " z=" + z);
            }
        }
    }
}