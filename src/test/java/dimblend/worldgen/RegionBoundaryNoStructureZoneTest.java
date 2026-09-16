package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class RegionBoundaryNoStructureZoneTest {
    private static final int BAND = BandIndex.DEFAULT_BAND_SIZE;
    private static final int HALF = RegionBoundaryNoStructureGeometry.HALF_WIDTH;

    /**
     * Production-shaped walls for the fixed script: region 1/2 (surface |
     * underground) at 4096, region 2/3 (underground | surface) at 6144, and the
     * mirrored west wall at -2048. Spawn 0/1 (both surface) at 0 and 2048, and
     * underground 5/6 at 12288, stay open.
     */
    private static final IntPredicate WALLS = wallX ->
            wallX == 4096 || wallX == 6144 || wallX == -2048;

    @Test
    void differentLaneWallBansBothSidesInclusively() {
        assertTrue(inside(4096));
        assertTrue(inside(4096 - HALF));
        assertTrue(inside(4096 + HALF));
        assertFalse(inside(4096 - HALF - 1));
        assertFalse(inside(4096 + HALF + 1));
    }

    @Test
    void sameNameContinuousBandsStayOpen() {
        assertFalse(inside(0), "spawn boundary region -1/0 is both surface");
        assertFalse(inside(2048), "region 0/1 is both surface");
        assertFalse(inside(2048 + 16));
        assertFalse(inside(2048 - 16));
        assertFalse(inside(5 * BAND), "region 5/6 is both underground");
        assertFalse(inside(5 * BAND + HALF));
        assertFalse(inside(5 * BAND - HALF));
    }

    @Test
    void bandInteriorFarFromWallsStaysOpen() {
        assertFalse(inside(BAND + BAND / 2));
        assertFalse(inside(BAND / 2));
    }

    @Test
    void negativeWallMirrorsTheEastSide() {
        assertTrue(inside(-2048));
        assertTrue(inside(-2048 - HALF));
        assertTrue(inside(-2048 + HALF));
        assertFalse(inside(-2048 - HALF - 1));
        assertFalse(inside(-2048 + HALF + 1));
    }

    @Test
    void originChunkFullyInsideOnlyWhenBothEdgesAreInTheZone() {
        int wall = 4096;
        int wallChunk = wall / 16;
        assertTrue(fullyInsideChunk(wallChunk));
        int westEdgeChunk = Math.floorDiv(wall - HALF, 16);
        assertFalse(fullyInsideChunk(westEdgeChunk), "chunk straddling the west rim");
        assertTrue(fullyInsideChunk(westEdgeChunk + 1));
        int eastEnd = wall + HALF;
        int eastEdgeChunk = Math.floorDiv(eastEnd, 16);
        assertTrue(fullyInsideChunk(eastEdgeChunk));
        assertFalse(fullyInsideChunk(eastEdgeChunk + 1));
    }

    @Test
    void aabbThatSpillsIntoTheZoneIsCaught() {
        int wall = 4096;
        assertTrue(aabb(wall + HALF - 5, wall + HALF + 40));
        assertTrue(aabb(wall - HALF - 40, wall - HALF + 5));
        assertTrue(aabb(wall - 10, wall + 10));
        assertFalse(aabb(wall + HALF + 1, wall + HALF + 80));
        assertFalse(aabb(wall - HALF - 80, wall - HALF - 1));
    }

    @Test
    void aabbAcrossASameNameBoundaryIsNotCaught() {
        assertFalse(aabb(2048 - 40, 2048 + 40), "surface 0/1 has no wall");
        assertFalse(aabb(5 * BAND - 40, 5 * BAND + 40), "underground 5/6 has no wall");
        assertTrue(aabb(4096 - 40, 4096 + 40), "surface/underground wall still catches");
    }

    private static boolean inside(int blockX) {
        return RegionBoundaryNoStructureGeometry.originInside(blockX, BAND, HALF, WALLS);
    }

    private static boolean fullyInsideChunk(int chunkX) {
        int minX = chunkX * 16;
        int maxX = minX + 15;
        return RegionBoundaryNoStructureGeometry.originFullyInside(minX, maxX, BAND, HALF, WALLS);
    }

    private static boolean aabb(int minX, int maxX) {
        return RegionBoundaryNoStructureGeometry.aabbIntersects(minX, maxX, BAND, HALF, WALLS);
    }
}
