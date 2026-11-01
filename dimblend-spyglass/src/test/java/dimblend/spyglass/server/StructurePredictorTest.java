package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of the prediction stage: budget, reach and cache bounds. The generation replay itself
 * needs a live generator and is covered by in-game verification, not here.
 */
class StructurePredictorTest {
    @Test
    void chunkDistanceUsesTheChunkCenter() {
        // Chunk 0,0 spans blocks 0..15, so its center is (8, 8).
        assertEquals(10.0, StructurePredictor.chunkDistance(8.0, -2.0, ChunkPos.asLong(0, 0)), 1.0E-9);
        assertEquals(
                Math.hypot(8.0, 8.0),
                StructurePredictor.chunkDistance(0.0, 0.0, ChunkPos.asLong(0, 0)),
                1.0E-9);
    }

    @Test
    void chunkDistanceHandlesNegativeChunks() {
        // Chunk -1,-1 spans blocks -16..-1, center (-8, -8).
        assertEquals(0.0, StructurePredictor.chunkDistance(-8.0, -8.0, ChunkPos.asLong(-1, -1)), 1.0E-9);
    }

    @Test
    void budgetLeavesRoomForTheNearestChunksOnly() {
        assertTrue(StructurePredictor.MAX_CHUNKS_PER_SCAN > 0, "a zero budget would never predict");
        assertTrue(StructurePredictor.MAX_CACHED_CHUNKS >= StructurePredictor.MAX_CHUNKS_PER_SCAN,
                "the cache must hold at least one scan's work");
    }

    @Test
    void reachCoversTheWholeDisplayRing() {
        // A start chunk that is only just outside the snapshot ring can still produce pieces that reach
        // into the ring, so the margin must be positive.
        assertTrue(StructurePredictor.START_CHUNK_MARGIN_BLOCKS > 0.0);
    }

    @Test
    void noAbsentCandidatesMeansNoWork() {
        // An empty input must not produce an empty non-empty prediction wrapper with work counters.
        StructurePredictor.Prediction empty = StructurePredictor.Prediction.empty();
        assertTrue(empty.outlines().isEmpty());
        assertEquals(0, empty.generatedChunks());
        assertEquals(0, empty.cacheHits());
        assertEquals(0, empty.skippedChunks());
        assertEquals(0, empty.failedChunks());
    }

    @Test
    void predictionOutlinesAreImmutable() {
        StructurePredictor.Prediction prediction =
                new StructurePredictor.Prediction(new ArrayList<>(), 1, 0, 0, 0);
        try {
            prediction.outlines().add(null);
            throw new AssertionError("Prediction outlines must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}