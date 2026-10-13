package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AetherSpawnChunkScanTest {
    @Test
    void aetherRadiiShrinkToTheChunksTheyCanReach() {
        // Swet banners and Aechor Plant deterrents on enchanted grass: 40 blocks; plain deterrents: 10.
        assertEquals(3, AetherSpawnChunkScan.chunkRadius(40));
        assertEquals(1, AetherSpawnChunkScan.chunkRadius(10));
    }

    @Test
    void theRadiusCoversEveryBlockWithinReachAndNoMore() {
        // Block distance is Euclidean over integer coordinates, so a block within r has |dx| <= r
        // on each horizontal axis; checking one axis covers both.
        for (int radius = 0; radius <= 70; radius++) {
            int chunks = AetherSpawnChunkScan.chunkRadius(radius);
            int farthest = 0;
            for (int spawn = -16; spawn < 16; spawn++) {
                int spawnChunk = Math.floorDiv(spawn, 16);
                for (int offset = -radius; offset <= radius; offset++) {
                    int distance = Math.abs(Math.floorDiv(spawn + offset, 16) - spawnChunk);
                    assertTrue(distance <= chunks, "radius " + radius + " spawn " + spawn + " offset " + offset);
                    farthest = Math.max(farthest, distance);
                }
            }
            assertEquals(chunks, farthest, "radius " + radius + " scans chunks no block can reach");
        }
    }

    @Test
    void nonPositiveRadiiScanOnlyTheSpawnChunk() {
        assertEquals(0, AetherSpawnChunkScan.chunkRadius(0));
        assertEquals(0, AetherSpawnChunkScan.chunkRadius(-5));
    }

    @Test
    void aetherOwnChunkRadiusIsKeptWhenAlreadySmaller() {
        // 1.5.10 passes the block radius 40 as the chunk radius.
        assertEquals(3, AetherSpawnChunkScan.scanRadius(40, 40));
        // A release that passes a proper chunk radius is left alone, never cut below what it reaches.
        assertEquals(3, AetherSpawnChunkScan.scanRadius(3, 40));
        assertEquals(2, AetherSpawnChunkScan.scanRadius(2, 40));
    }
}
