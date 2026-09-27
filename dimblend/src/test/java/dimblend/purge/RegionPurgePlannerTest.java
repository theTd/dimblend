package dimblend.purge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RegionPurgePlannerTest {

    @Test
    void keyMatchesChunkPosPackingIncludingNegatives() {
        // ChunkPos.asLong(x, z) = x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32
        assertEquals(0x00000000FFFFFFFDL, RegionPurgePlanner.key(-3, 0));
        assertEquals(0xFFFFFFFF00000002L, RegionPurgePlanner.key(2, -1));
        long key = RegionPurgePlanner.key(-40000, 17);
        assertEquals(-40000, RegionPurgePlanner.keyX(key));
        assertEquals(17, RegionPurgePlanner.keyZ(key));
    }

    @Test
    void chunkToRegionFloorsNegativeChunks() {
        assertEquals(0, RegionPurgePlanner.regionOfChunk(31));
        assertEquals(1, RegionPurgePlanner.regionOfChunk(32));
        assertEquals(-1, RegionPurgePlanner.regionOfChunk(-1));
        assertEquals(-1, RegionPurgePlanner.regionOfChunk(-32));
        assertEquals(-2, RegionPurgePlanner.regionOfChunk(-33));
        long chunk = RegionPurgePlanner.key(-33, 40);
        assertEquals(RegionPurgePlanner.key(-2, 1), RegionPurgePlanner.regionKeyOfChunk(chunk));
    }

    @Test
    void distanceIsZeroInsideAndCountsToNearestColumn() {
        // region 2 covers chunk X 64..95
        assertEquals(0, RegionPurgePlanner.chunkDistanceX(2, 64));
        assertEquals(0, RegionPurgePlanner.chunkDistanceX(2, 95));
        assertEquals(1, RegionPurgePlanner.chunkDistanceX(2, 63));
        assertEquals(5, RegionPurgePlanner.chunkDistanceX(2, 100));
        assertEquals(Integer.MAX_VALUE, RegionPurgePlanner.nearestCenterDistance(2, new int[0]));
        assertEquals(1, RegionPurgePlanner.nearestCenterDistance(2, new int[] {-500, 63, 400}));
    }

    @Test
    void effectiveKeepCoversPregenAndViewDistance() {
        assertEquals(96, RegionPurgePlanner.effectiveKeepChunks(96, 64, 64, 12));
        assertEquals(208, RegionPurgePlanner.effectiveKeepChunks(96, 200, 64, 12));
        assertEquals(40, RegionPurgePlanner.effectiveKeepChunks(32, 16, 16, 32));
    }

    @Test
    void parsesOnlyVanillaRegionFileNames() {
        assertEquals(OptionalLong.of(RegionPurgePlanner.key(-3, 0)), RegionPurgePlanner.parseRegionFileName("r.-3.0.mca"));
        assertEquals(OptionalLong.of(RegionPurgePlanner.key(12, -1)), RegionPurgePlanner.parseRegionFileName("r.12.-1.mca"));
        assertTrue(RegionPurgePlanner.parseRegionFileName("r.1.2.mca.tmp").isEmpty());
        assertTrue(RegionPurgePlanner.parseRegionFileName("r.1.2.3.mca").isEmpty());
        assertTrue(RegionPurgePlanner.parseRegionFileName("r.a.2.mca").isEmpty());
        assertTrue(RegionPurgePlanner.parseRegionFileName("c.1.2.mcc").isEmpty());
        assertTrue(RegionPurgePlanner.parseRegionFileName("r.1.2.slvlr").isEmpty());
    }

    @Test
    void oversizedChunkFilesMatchTheirRegion() {
        assertTrue(RegionPurgePlanner.isOversizedChunkOf("c.-33.5.mcc", -2, 0));
        assertFalse(RegionPurgePlanner.isOversizedChunkOf("c.-32.5.mcc", -2, 0));
        assertFalse(RegionPurgePlanner.isOversizedChunkOf("r.-2.0.mca", -2, 0));
    }

    @Test
    void regionBecomesReadyOnlyAfterUnbrokenIdle() {
        RegionPurgePlanner planner = new RegionPurgePlanner();
        long far = RegionPurgePlanner.key(-10, 0);
        Set<Long> disk = Set.of(far);
        assertTrue(planner.plan(disk, key -> true, key -> 0, 100, 200, 8).length == 0);
        assertTrue(planner.plan(disk, key -> true, key -> 0, 250, 200, 8).length == 0);
        assertEquals(1, planner.plan(disk, key -> true, key -> 0, 300, 200, 8).length);
    }

    @Test
    void ineligibleScanRestartsTheIdleTimer() {
        RegionPurgePlanner planner = new RegionPurgePlanner();
        long far = RegionPurgePlanner.key(-10, 0);
        Set<Long> disk = Set.of(far);
        planner.plan(disk, key -> true, key -> 0, 0, 200, 8);
        planner.plan(disk, key -> false, key -> 0, 150, 200, 8);
        assertTrue(planner.plan(disk, key -> true, key -> 0, 250, 200, 8).length == 0);
        assertEquals(1, planner.plan(disk, key -> true, key -> 0, 450, 200, 8).length);
    }

    @Test
    void regionMissingFromDiskIsDropped() {
        RegionPurgePlanner planner = new RegionPurgePlanner();
        long far = RegionPurgePlanner.key(-10, 0);
        planner.plan(Set.of(far), key -> true, key -> 0, 0, 200, 8);
        assertEquals(1, planner.tracked());
        planner.plan(new HashSet<>(), key -> true, key -> 0, 10, 200, 8);
        assertEquals(0, planner.tracked());
    }

    @Test
    void readyRegionsAreFarthestFirstAndCapped() {
        RegionPurgePlanner planner = new RegionPurgePlanner();
        long near = RegionPurgePlanner.key(-5, 0);
        long mid = RegionPurgePlanner.key(-8, 0);
        long far = RegionPurgePlanner.key(-20, -1);
        Set<Long> disk = Set.of(near, mid, far);
        int[] centers = {0};
        long[] ready = planner.plan(
                disk,
                key -> true,
                key -> RegionPurgePlanner.nearestCenterDistance(RegionPurgePlanner.keyX(key), centers),
                0,
                0,
                2
        );
        assertEquals(2, ready.length);
        assertEquals(far, ready[0]);
        assertEquals(mid, ready[1]);
    }
}
