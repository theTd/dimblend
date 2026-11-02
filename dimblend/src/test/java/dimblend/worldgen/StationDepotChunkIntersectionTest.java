package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * R6 depot vs. the chunk a decoration pass holds. {@code ChunkAccess#setBlockState}
 * does not throw on an out-of-chunk coordinate: it wraps the coordinate with
 * {@code & 15} and writes into an unrelated column of the same chunk. So the pass
 * must decide "does this block / this boundary land in THIS chunk" itself, and it
 * has to decide it before writing. This class pins those decisions as pure
 * functions, so neither the Z wrap nor the west-half gap can come back.
 */
class StationDepotChunkIntersectionTest {
    private static final int BAND = BandIndex.DEFAULT_BAND_SIZE;
    /** The boundary the user reported in game: its depot was the one that went wrong. */
    private static final int B = 8192;
    /** Scripted lanes: every region is surface, so only the X=0 rule can exclude a depot. */
    private static final IntFunction<String> LANES = region -> "surface";

    /**
     * A — chunk 512 spans X 8192..8207. Its Z span is 0..15. The deck cell at
     * z=-15 belongs to chunkZ=-1; writing it here wraps it onto z=1. That wrap is
     * why every Z chunk around X=8192 held a 16-row slab instead of the 31 rows the
     * deck really has.
     */
    @Test
    void aCellOutsideTheChunksZSpanIsNotWrittenHere() {
        int minX = 512 * 16;
        int maxX = minX + 15;
        assertTrue(StationDepotGeometry.chunkContainsBlock(B, 0, minX, maxX, 0, 15),
                "the deck's centre row is this chunk's business");
        assertFalse(StationDepotGeometry.chunkContainsBlock(B, -15, minX, maxX, 0, 15),
                "z=-15 wraps onto z=1: it must be left to chunkZ=-1");
        assertTrue(StationDepotGeometry.chunkContainsBlock(B, -15, minX, maxX, -16, -1),
                "and chunkZ=-1 is the chunk that does own it");
        // The real cell list, not just one synthetic block.
        for (StationDepotGeometry.Cell cell : StationDepotGeometry.placedCells(B)) {
            boolean inThisChunk = cell.x() >= minX && cell.x() <= maxX
                    && cell.z() >= 0 && cell.z() <= 15;
            assertEquals(inThisChunk, StationDepotGeometry.chunkContainsBlock(
                    cell.x(), cell.z(), minX, maxX, 0, 15), "cell " + cell);
        }
    }

    /**
     * B — chunk 510 spans X 8160..8175 and lies west of the boundary column. The
     * depot writes from B-27 = 8165, so this chunk holds the west shell column and
     * the first carved columns of the hall. The old enumeration only started at
     * the first boundary at or after the chunk's west edge, so this chunk built
     * nothing: the west half of the hall, both buffers and the west side of the merge
     * were missing.
     */
    @Test
    void aChunkWestOfTheBoundaryBelongsToTheDepot() {
        int minX = 510 * 16;
        int maxX = minX + 15;
        assertTrue(StationDepotGeometry.writeMinBlockX(B) <= maxX,
                "the depot's write span must reach west of the boundary");
        assertTrue(StationDepotGeometry.boundaryIntersectsChunkX(B, minX, maxX));
        assertEquals(List.of(B), StationDepotGeometry.boundariesTouchingChunkX(BAND, minX, maxX),
                "chunk 510 must process boundary 8192, and only it");
    }

    /**
     * C — chunk 514 spans X 8224..8239, east of the boundary. It holds the far end
     * of the hall, the second (negative-siding) merge and the east shell. Same bug
     * as B, mirrored: the old loop looked for a boundary inside the chunk and found
     * none.
     */
    @Test
    void aChunkEastOfTheBoundaryBelongsToTheDepot() {
        int minX = 514 * 16;
        int maxX = minX + 15;
        assertTrue(StationDepotGeometry.writeMaxBlockX(B) >= minX,
                "the depot's write span must reach east of the boundary");
        assertTrue(StationDepotGeometry.boundaryIntersectsChunkX(B, minX, maxX));
        assertEquals(List.of(B), StationDepotGeometry.boundariesTouchingChunkX(BAND, minX, maxX),
                "chunk 514 must process boundary 8192, and only it");
    }

    /**
     * Every chunk column the depot's 76-wide write span overlaps must process it:
     * 510..515 for B=8192. This is the direct pin for "the west half and the east
     * merge are missing", because exactly one of those six ran before the fix.
     */
    @Test
    void everyChunkColumnTheDepotOverlapsProcessesIt() {
        for (int chunkX = 510; chunkX <= 515; chunkX++) {
            int minX = chunkX * 16;
            assertTrue(StationDepotGeometry.boundariesTouchingChunkX(BAND, minX, minX + 15).contains(B),
                    "chunk " + chunkX + ' ' + minX + ".." + (minX + 15) + " is under the depot");
        }
        assertFalse(StationDepotGeometry.boundariesTouchingChunkX(BAND, 509 * 16, 509 * 16 + 15).contains(B),
                "chunk 509 ends at 8159, 6 blocks west of the shell at 8165");
        assertFalse(StationDepotGeometry.boundariesTouchingChunkX(BAND, 516 * 16, 516 * 16 + 15).contains(B),
                "chunk 516 starts at 8256, 16 blocks east of the shell at 8240");
    }

    /**
     * D — chunk 512 with chunkZ 5 spans z 80..95. The depot spans z -16..16, so
     * nothing of it may land here. ChunkZ -1, 0 and 1 are the only rows that hold
     * depot blocks (the shell ring reaches z=+-16); chunkZ 2 and -2 are outside it.
     */
    @Test
    void aChunkFarFromTheDepotInZSpanWritesNothing() {
        int minX = 512 * 16;
        int maxX = minX + 15;
        assertFalse(StationDepotGeometry.depotIntersectsChunkZ(80, 95));
        for (StationDepotGeometry.Cell cell : StationDepotGeometry.placedCells(B)) {
            assertFalse(StationDepotGeometry.chunkContainsBlock(cell.x(), cell.z(), minX, maxX, 80, 95),
                    "chunkZ 5 must write no depot cell: " + cell);
        }
        assertTrue(StationDepotGeometry.depotIntersectsChunkZ(-16, -1), "chunkZ -1 holds z -16..-1");
        assertTrue(StationDepotGeometry.depotIntersectsChunkZ(0, 15), "chunkZ 0 holds z 0..15");
        assertTrue(StationDepotGeometry.depotIntersectsChunkZ(16, 31),
                "chunkZ 1 holds the shell ring's z=16 column");
        assertFalse(StationDepotGeometry.depotIntersectsChunkZ(32, 47), "chunkZ 2 starts past the shell");
        assertFalse(StationDepotGeometry.depotIntersectsChunkZ(-32, -17), "chunkZ -2 ends before the shell");
    }

    /**
     * E — the spawn boundary at X=0 still gets no depot: the starter depot and its
     * bedrock wall own that column. This is pre-existing behaviour and must stay
     * green before and after the fix.
     */
    @Test
    void theSpawnBoundaryStillBuildsNoDepot() {
        assertFalse(StationDepotGeometry.buildsDepot(0, BAND, LANES));
        assertTrue(StationDepotGeometry.buildsDepot(B, BAND, LANES),
                "8192 is an ordinary surface/surface boundary once lanes allow it");
    }

    /**
     * The fix enumerates chunk -1's boundary (0), and the X=0 rule is then what stops
     * it. Pinned separately from E so E keeps pinning the pre-existing rule only.
     */
    @Test
    void theSpawnBoundaryIsEnumeratedThenRejected() {
        assertEquals(List.of(0), StationDepotGeometry.boundariesTouchingChunkX(BAND, -16, -1),
                "chunk -1 reaches only the spawn boundary");
        assertFalse(StationDepotGeometry.buildsDepot(0, BAND, LANES));
    }

    /** The mirrored, negative boundary is the same bug on the other side: -2048. */
    @Test
    void theNegativeBoundaryIsFoundFromBothSides() {
        assertTrue(StationDepotGeometry.boundariesTouchingChunkX(BAND, -2064, -2049)
                .contains(-2048), "chunk -129 is the west half of the X=-2048 depot");
        assertTrue(StationDepotGeometry.boundariesTouchingChunkX(BAND, -2032, -2017)
                .contains(-2048), "chunk -127 is the east half of the X=-2048 depot");
    }

    /**
     * A chunk can touch two boundaries; bandSize is a parameter, not a constant 2048,
     * and its loop must not assume "one boundary, at most". With band 48 and the same
     * 82-wide depot, chunk 512 touches 8160 and 8208 at once.
     */
    @Test
    void aChunkCanTouchTwoBoundaries() {
        assertEquals(List.of(8160, 8208),
                StationDepotGeometry.boundariesTouchingChunkX(48, 8192, 8207));
    }
}
