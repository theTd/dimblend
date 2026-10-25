package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.junit.jupiter.api.Test;

class StructureCandidatesTest {
    private static final long SEED = 0x1234ABCDL;
    private static final int SPACING = 9;
    private static final int SEPARATION = 3;
    private static final int SALT = 73129;

    @Test
    void scanWindowCoversTheVisibleRingPlusPaddingPlusStartMargin() {
        assertEquals(64, StructureCandidates.VISIBLE_RADIUS_CHUNKS);
        assertEquals(3, StructureCandidates.PAD_CHUNKS);
        assertEquals(32, StructureCandidates.START_MARGIN_CHUNKS);
        assertEquals(99, StructureCandidates.SCAN_RADIUS_CHUNKS);
        assertTrue(StructureCandidates.PAD_CHUNKS * 16 >= SpyglassRefreshPolicy.MOVE_THRESHOLD_BLOCKS,
                "the snapshot ring padding must cover a full movement refresh interval");
        assertTrue(SpyglassRefreshPolicy.SNAPSHOT_PAD_BLOCKS >= SpyglassRefreshPolicy.MOVE_THRESHOLD_BLOCKS);
    }

    @Test
    void windowIncludesMarginAtNegativeCoordinates() {
        StructureCandidates.ChunkWindow window = StructureCandidates.windowFor(
                -3, -7, StructureCandidates.SCAN_RADIUS_CHUNKS);

        assertEquals(-102, window.minX());
        assertEquals(96, window.maxX());
        assertEquals(-106, window.minZ());
        assertEquals(92, window.maxZ());
        assertTrue(window.contains(-102, -106));
        assertTrue(window.contains(96, 92));
        assertFalse(window.contains(-103, -106));
        assertFalse(window.contains(96, 93));
    }

    @Test
    void randomSpreadEnumerationMatchesBruteForceInsideNegativeWindow() {
        StructureCandidates.ChunkWindow window = new StructureCandidates.ChunkWindow(-70, -63, -34, -27);

        List<ChunkPos> actual = StructureCandidates.randomSpreadChunks(
                SPACING, window, StructureCandidatesTest::potentialChunk);
        Set<ChunkPos> expected = new HashSet<>();
        for (int x = window.minX(); x <= window.maxX(); x++) {
            for (int z = window.minZ(); z <= window.maxZ(); z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (potentialChunk(x, z).equals(pos)) {
                    expected.add(pos);
                }
            }
        }

        assertFalse(actual.isEmpty());
        assertEquals(expected, new HashSet<>(actual));
        assertEquals(actual.size(), new HashSet<>(actual).size());
        for (ChunkPos pos : actual) {
            assertTrue(window.contains(pos.x, pos.z));
            assertTrue(pos.x < 0);
            assertTrue(pos.z < 0);
        }
    }

    @Test
    void bandFilteringKeepsOnlyChunksOwnedByTheBand() {
        StructureCandidates.ChunkWindow band = new StructureCandidates.ChunkWindow(0, -5, 10, 5);
        List<ChunkPos> positions = List.of(
                new ChunkPos(-1, 0),
                new ChunkPos(0, 0),
                new ChunkPos(10, 5),
                new ChunkPos(11, 0),
                new ChunkPos(5, 6),
                new ChunkPos(5, -6));

        assertEquals(
                List.of(new ChunkPos(0, 0), new ChunkPos(10, 5)),
                StructureCandidates.filterToWindow(positions, band));
    }

    @Test
    void randomSpreadEnumerationRespectsEachBandWindowSeparately() {
        StructureCandidates.ChunkWindow west = new StructureCandidates.ChunkWindow(-30, -30, -1, 30);
        StructureCandidates.ChunkWindow east = new StructureCandidates.ChunkWindow(0, -30, 30, 30);

        List<ChunkPos> westChunks = StructureCandidates.randomSpreadChunks(
                SPACING, west, StructureCandidatesTest::potentialChunk);
        List<ChunkPos> eastChunks = StructureCandidates.randomSpreadChunks(
                SPACING, east, StructureCandidatesTest::potentialChunk);

        assertFalse(westChunks.isEmpty());
        assertFalse(eastChunks.isEmpty());
        for (ChunkPos pos : westChunks) {
            assertTrue(pos.x < 0, "west band must not enumerate east chunks");
        }
        for (ChunkPos pos : eastChunks) {
            assertTrue(pos.x >= 0, "east band must not enumerate west chunks");
        }
        Set<ChunkPos> all = new HashSet<>(westChunks);
        all.addAll(eastChunks);
        assertEquals(westChunks.size() + eastChunks.size(), all.size());
    }

    @Test
    void rejectsInvertedWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new StructureCandidates.ChunkWindow(1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new StructureCandidates.ChunkWindow(0, 1, 0, 0));
    }

    private static ChunkPos potentialChunk(int chunkX, int chunkZ) {
        int cellX = Math.floorDiv(chunkX, SPACING);
        int cellZ = Math.floorDiv(chunkZ, SPACING);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureWithSalt(SEED, cellX, cellZ, SALT);
        int spread = SPACING - SEPARATION;
        int offsetX = RandomSpreadType.LINEAR.evaluate(random, spread);
        int offsetZ = RandomSpreadType.LINEAR.evaluate(random, spread);
        return new ChunkPos(cellX * SPACING + offsetX, cellZ * SPACING + offsetZ);
    }
}
