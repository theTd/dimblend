package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The chunk span of a rotating region, which the spyglass scan window is clamped to.
 *
 * <p>Exercises the pure {@code (bandSize, blockX)} forms: the rule only depends on the region
 * layout, so no level is needed. The cases that matter are the two sides of a border and the
 * negative side of the world, where a shift ({@code >> 4}) and {@code Math.floorDiv} disagree.
 */
class RegionLanesChunkBoundsTest {
    private static final int BAND = 2048;
    private static final int UNALIGNED_BAND = 1000;

    @Test
    void theFirstRegionOfTheWorldSpansChunksZeroTo127() {
        assertEquals(0, RegionLanes.regionMinChunkX(BAND, 0));
        assertEquals(127, RegionLanes.regionMaxChunkX(BAND, 0));
        assertEquals(0, RegionLanes.regionMinChunkX(BAND, 2047));
        assertEquals(127, RegionLanes.regionMaxChunkX(BAND, 2047));
    }

    @Test
    void theNextRegionStartsOnTheFirstBlockPastTheBorder() {
        assertEquals(128, RegionLanes.regionMinChunkX(BAND, 2048));
        assertEquals(255, RegionLanes.regionMaxChunkX(BAND, 2048));
        assertEquals(128, RegionLanes.regionMinChunkX(BAND, 4095));
        assertEquals(255, RegionLanes.regionMaxChunkX(BAND, 4095));
    }

    @Test
    void theNegativeSideFloorsInsteadOfTruncating() {
        // -1 is the last block of region -1, not the first of region 0.
        assertEquals(-128, RegionLanes.regionMinChunkX(BAND, -1));
        assertEquals(-1, RegionLanes.regionMaxChunkX(BAND, -1));
        assertEquals(-128, RegionLanes.regionMinChunkX(BAND, -2048));
        assertEquals(-256, RegionLanes.regionMinChunkX(BAND, -2049));
        assertEquals(-129, RegionLanes.regionMaxChunkX(BAND, -2049));
    }

    @Test
    void anUnalignedBandSizeFloorsBothEndsIntoWholeChunks() {
        // Region 0 covers blocks 0..999: chunk 62 (992..1007) is shared with region 1.
        assertEquals(0, RegionLanes.regionMinChunkX(UNALIGNED_BAND, 0));
        assertEquals(62, RegionLanes.regionMaxChunkX(UNALIGNED_BAND, 999));
        assertEquals(62, RegionLanes.regionMinChunkX(UNALIGNED_BAND, 1000));
        assertEquals(124, RegionLanes.regionMaxChunkX(UNALIGNED_BAND, 1999));
        // -1 belongs to region -1, which starts at block -1000 (chunk -63).
        assertEquals(-63, RegionLanes.regionMinChunkX(UNALIGNED_BAND, -1));
        assertEquals(-1, RegionLanes.regionMaxChunkX(UNALIGNED_BAND, -1));
    }

    @Test
    void anUnknownBandSizeLeavesTheColumnWhereItIs() {
        assertEquals(Math.floorDiv(2047, 16), RegionLanes.regionMinChunkX(0, 2047));
        assertEquals(Math.floorDiv(-2049, 16), RegionLanes.regionMaxChunkX(-1, -2049));
    }

    @Test
    void theSpanAlwaysContainsTheColumnItself() {
        int[] columns = {0, 1, 15, 16, 2047, 2048, -1, -16, -2048, -2049, 123_456, -123_456};
        for (int bandSize : new int[] {BAND, UNALIGNED_BAND, 16, 100}) {
            for (int blockX : columns) {
                int chunk = Math.floorDiv(blockX, 16);
                assertTrue(RegionLanes.regionMinChunkX(bandSize, blockX) <= chunk,
                        "min chunk of " + blockX + " at band " + bandSize);
                assertTrue(RegionLanes.regionMaxChunkX(bandSize, blockX) >= chunk,
                        "max chunk of " + blockX + " at band " + bandSize);
            }
        }
    }

    @Test
    void theRegionTestAgreesWithTheSpan() {
        // A column inside the span of another region's chunk is still a different region: the
        // builder clamps by chunk, the filter decides by block. Both use the same region rule.
        assertTrue(RegionLanes.sameRegion(BAND, 0, 2047));
        assertFalse(RegionLanes.sameRegion(BAND, 0, 2048));
        assertFalse(RegionLanes.sameRegion(BAND, 0, -1));
        assertTrue(RegionLanes.sameRegion(BAND, -1, -2048));
        assertFalse(RegionLanes.sameRegion(0, 0, 10));
    }
}
