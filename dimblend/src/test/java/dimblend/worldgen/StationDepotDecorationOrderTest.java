package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Verifies StationDepot tree-like feature retreat bounds and upward-supported flora cleanup so
 * overhanging biome decorations never survive around the depot hall.
 */
class StationDepotDecorationOrderTest {
    private static final int BAND = BandIndex.DEFAULT_BAND_SIZE;
    private static final int B = 2048;

    private static final IntFunction<String> LANES = region -> switch (region) {
        case -2, -1, 0, 1 -> "surface";
        default -> "underground";
    };

    @Test
    void treeRetreatCoversEntireDepotWidthAndHeightAtSurfaceBoundaries() {
        // Siding and platform apron columns sit outside OakTrackCorridor.VAULT_RADIUS (9).
        assertTrue(StationDepotGeometry.blocksTreeLikeOrigin(B, 0, 10, BAND, LANES));
        assertTrue(StationDepotGeometry.blocksTreeLikeOrigin(B, 0, -15, BAND, LANES));
        assertTrue(StationDepotGeometry.blocksTreeLikeOrigin(
                B + StationDepotGeometry.CAVERN_MIN_DX, 5, 15, BAND, LANES));
        assertTrue(StationDepotGeometry.blocksTreeLikeOrigin(
                B + StationDepotGeometry.CAVERN_MAX_DX,
                StationDepotGeometry.CAVERN_APEX_DY + 1,
                -10,
                BAND,
                LANES));
        assertTrue(StationDepotGeometry.blocksTreeLikeOrigin(B, -48, 12, BAND, LANES));

        // Outside Z, X, Y, spawn boundary B=0, or surface/underground walled boundaries stay unblocked.
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(B, 0, 16, BAND, LANES));
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(B, 0, -16, BAND, LANES));
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(
                B + StationDepotGeometry.CAVERN_MIN_DX - 1, 0, 12, BAND, LANES));
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(
                B + StationDepotGeometry.CAVERN_MAX_DX + 1, 0, 12, BAND, LANES));
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(B, -49, 12, BAND, LANES));
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(
                B, StationDepotGeometry.CAVERN_APEX_DY + 2, 12, BAND, LANES));
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(0, 0, 12, BAND, LANES));
        assertFalse(StationDepotGeometry.blocksTreeLikeOrigin(4096, 0, 12, BAND, LANES));
    }
}
