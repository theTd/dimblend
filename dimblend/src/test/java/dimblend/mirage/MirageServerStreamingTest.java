package dimblend.mirage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The live capture (fast flight to seam X = 4096, then hovering 34 blocks away) showed four
 * compounding failures: one 218-chunk all-or-nothing batch, a cancellation storm on every request,
 * an opposite-side prefetch starving the active side, and the front wall arriving after chunks the
 * player never looks at. Everything that decides whether that is fixed is a pure function or a
 * plain data structure, so it is pinned here without a Minecraft server.
 */
class MirageServerStreamingTest {
    private static final int BAND = 2048;
    private static final int SLAB = 2 * BAND;
    private static final byte WEST = MirageChunkKey.WEST_BAND_INTO_EAST;
    private static final int SEAM_CHUNK_X = SLAB >> 4;
    /** The cap the capture asked for: 12 chunks deep x 10 chunks half width = 218 chunks. */
    private static final int DEPTH = 12;
    private static final int HALF_WIDTH = 10;

    @Test
    void theCapOfTheLiveCaptureHas218Chunks() {
        assertEquals(218, cap().size(), "precondition: 12 x 10 elliptical cap");
        assertEquals(12, MirageServerManager.MINI_BATCH_SIZE);
        assertEquals(12, MirageServerManager.MAX_SENDS_PER_TICK);
    }

    /**
     * The wall the player looks at — the seam band under the eye Z — must be in the first
     * mini-batch, not behind deeper bands that are invisible from 34 blocks away.
     */
    @Test
    void theSeamWallUnderTheCameraLandsInTheFirstMiniBatch() {
        List<MirageChunkKey> targets = cap();
        double eyeX = SLAB - 34.0; // hovering 34 blocks west of the seam, as in the capture
        List<MirageChunkKey> ordered = MirageServerManager.orderKeysForStreaming(
                targets, SLAB, WEST, eyeX, 0.0);
        assertEquals(targets.size(), ordered.size(), "ordering must neither drop nor add a chunk");

        List<MirageChunkKey> firstBatch = ordered.subList(0, MirageServerManager.MINI_BATCH_SIZE);
        for (int chunkZ = -3; chunkZ <= 2; chunkZ++) {
            MirageChunkKey front = new MirageChunkKey(SLAB, WEST, SEAM_CHUNK_X, chunkZ);
            assertTrue(firstBatch.contains(front),
                    "seam wall chunk Z " + chunkZ + " must be in the very first batch");
        }
        assertEquals(SEAM_CHUNK_X, ordered.get(0).chunkX(),
                "the first chunk streamed is the seam band, not the chunk under the camera");
        for (MirageChunkKey key : firstBatch) {
            assertTrue(key.chunkX() <= SEAM_CHUNK_X + 1,
                    "the first batch stays in the two front bands, got " + key);
        }
        assertFalse(firstBatch.contains(new MirageChunkKey(SLAB, WEST, SEAM_CHUNK_X + 6, 0)),
                "precondition: six chunks past the seam is not a front chunk");

        // The eye moves along Z: the front tier follows it instead of staying pinned to chunk Z 0.
        List<MirageChunkKey> shifted = MirageServerManager.orderKeysForStreaming(
                targets, SLAB, WEST, eyeX, 10 * 16.0);
        List<MirageChunkKey> shiftedHead =
                shifted.subList(0, MirageServerManager.MINI_BATCH_SIZE);
        for (int chunkZ = 7; chunkZ <= 10; chunkZ++) {
            assertTrue(shiftedHead.contains(new MirageChunkKey(SLAB, WEST, SEAM_CHUNK_X, chunkZ)),
                    "front tier must track the camera Z, missing chunk Z " + chunkZ);
        }
    }

    /** A 218-chunk cap becomes nineteen mini-batches, and the first one is the ordered head. */
    @Test
    void miniBatchesSplitTheCapIntoTwelveChunkStreams() {
        List<MirageChunkKey> ordered = MirageServerManager.orderKeysForStreaming(
                cap(), SLAB, WEST, SLAB - 34.0, 0.0);
        MirageSchedulerQueue<String> queue =
                new MirageSchedulerQueue<>(MirageServerManager.MINI_BATCH_SIZE);
        queue.submit("player/west", 1L, true, ordered);

        Set<MirageChunkKey> done = new HashSet<>();
        List<MirageSchedulerQueue.Batch<String>> batches = new ArrayList<>();
        MirageSchedulerQueue.Batch<String> batch;
        while ((batch = queue.takeNext(done::contains)) != null) {
            batches.add(batch);
            done.addAll(batch.keys());
        }
        assertEquals(19, batches.size(), "218 chunks = 18 full batches + one of 2");
        for (int i = 0; i < batches.size() - 1; i++) {
            assertEquals(MirageServerManager.MINI_BATCH_SIZE, batches.get(i).keys().size());
        }
        assertEquals(2, batches.get(batches.size() - 1).keys().size());
        assertEquals(ordered.subList(0, MirageServerManager.MINI_BATCH_SIZE), batches.get(0).keys(),
                "the first batch is exactly the streaming order's head");
        assertEquals(218, done.size(), "every chunk is scheduled exactly once");
        assertTrue(queue.isEmpty(), "no order survives its last batch");
    }

    /** The player's own side never waits behind an opposite-side prefetch, ever again. */
    @Test
    void primaryPreemptsAnOppositePrefetchBetweenBatches() {
        MirageSchedulerQueue<String> queue =
                new MirageSchedulerQueue<>(MirageServerManager.MINI_BATCH_SIZE);
        queue.submit("opposite", 2L, false, keys(1, 40));
        queue.submit("primary", 3L, true, keys(0, 30));
        Set<MirageChunkKey> done = new HashSet<>();
        List<String> channels = new ArrayList<>();
        MirageSchedulerQueue.Batch<String> batch;
        while ((batch = queue.takeNext(done::contains)) != null) {
            channels.add(batch.channel());
            assertTrue(batch.primary() || channels.size() > 3,
                    "not a single opposite batch while the primary order still has work");
            done.addAll(batch.keys());
        }
        assertEquals(
                List.of("primary", "primary", "primary", "opposite", "opposite", "opposite", "opposite"),
                channels, "primary runs to completion before the prefetch gets a batch");
        assertTrue(queue.isEmpty());
    }

    /** Among equal priority the most recently submitted order runs, so a live view wins. */
    @Test
    void equalPriorityRunsTheMostRecentOrder() {
        MirageSchedulerQueue<String> queue =
                new MirageSchedulerQueue<>(MirageServerManager.MINI_BATCH_SIZE);
        queue.submit("older", 1L, false, keys(0, 20));
        queue.submit("newer", 2L, false, keys(1, 20));
        Set<MirageChunkKey> done = new HashSet<>();
        MirageSchedulerQueue.Batch<String> first = queue.takeNext(done::contains);
        assertNotNull(first);
        assertEquals("newer", first.channel());
        assertEquals(2L, first.seamId());
    }

    /** Nothing already in the cache may be generated a second time. */
    @Test
    void alreadyCachedChunksAreSkippedWithoutRegeneratingThem() {
        List<MirageChunkKey> ordered = MirageServerManager.orderKeysForStreaming(
                cap(), SLAB, WEST, SLAB - 34.0, 0.0);
        Set<MirageChunkKey> cache = new HashSet<>(ordered.subList(0, 30));
        MirageSchedulerQueue<String> queue =
                new MirageSchedulerQueue<>(MirageServerManager.MINI_BATCH_SIZE);
        queue.submit("player/west", 1L, true, ordered);

        List<MirageChunkKey> generated = new ArrayList<>();
        MirageSchedulerQueue.Batch<String> batch;
        while ((batch = queue.takeNext(cache::contains)) != null) {
            for (MirageChunkKey key : batch.keys()) {
                assertFalse(cache.contains(key), "a cached chunk must never be regenerated: " + key);
                assertTrue(cache.add(key), "a chunk must not be generated twice: " + key);
            }
            generated.addAll(batch.keys());
        }
        assertEquals(218 - 30, generated.size(), "only the uncached remainder is generated");
    }

    /** A cached-only order is dropped instead of producing empty batches forever. */
    @Test
    void anOrderWhoseKeysAreAllCachedIsNotScheduled() {
        List<MirageChunkKey> keys = keys(0, 3);
        Set<MirageChunkKey> cache = new HashSet<>(keys);
        MirageSchedulerQueue<String> queue =
                new MirageSchedulerQueue<>(MirageServerManager.MINI_BATCH_SIZE);
        queue.submit("done", 1L, true, keys);
        assertNull(queue.takeNext(cache::contains));
        assertTrue(queue.isEmpty(), "an exhausted order is removed");
    }

    /** Same seam: queue kept. New seam: queue reset, stale batches refused. */
    @Test
    void sameSeamUpdatesKeepQueuedPayloadsAndANewSeamResetsTheQueue() {
        MirageServerManager.PlayerQueue queue = new MirageServerManager.PlayerQueue();
        long seamA = seamId(SLAB, WEST);
        long seamB = seamId(SLAB, MirageChunkKey.EAST_BAND_INTO_WEST);

        queue.switchSeamIfNeeded(seamA);
        assertEquals(seamA, queue.currentSeamId());
        List<MirageChunkPayload> firstBatch = List.of(
                payload(seamA, 0), payload(seamA, 1), payload(seamA, 2));
        assertTrue(queue.enqueueBatch(seamA, firstBatch));
        assertEquals(3, queue.queuedCount());

        // The player moved one chunk along Z / the cap grew: the queued payloads stay.
        queue.switchSeamIfNeeded(seamA);
        List<MirageChunkPayload> grown = List.of(payload(seamA, 2), payload(seamA, 3));
        assertTrue(queue.enqueueBatch(seamA, grown));
        assertEquals(4, queue.queuedCount(), "a duplicate key must not be queued twice");
        assertTrue(queue.isQueued(new MirageChunkKey(SLAB, WEST, SEAM_CHUNK_X, 0)));
        assertTrue(queue.isQueued(new MirageChunkKey(SLAB, WEST, SEAM_CHUNK_X, 3)));

        // Crossing to another seam resets everything, and an in-flight batch of the old seam is
        // refused instead of polluting the new one.
        queue.switchSeamIfNeeded(seamB);
        assertEquals(seamB, queue.currentSeamId());
        assertEquals(0, queue.queuedCount());
        List<MirageChunkPayload> staleSeamBatch = List.of(payload(seamA, 4));
        assertFalse(queue.enqueueBatch(seamA, staleSeamBatch));
        assertEquals(0, queue.queuedCount());
        assertTrue(queue.enqueueBatch(seamB, List.of(payload(seamB, 0))));

        MirageChunkPayload polled = queue.poll();
        assertNotNull(polled);
        assertEquals(0, queue.queuedCount(), "poll frees the queued key");
    }

    private static List<MirageChunkKey> cap() {
        List<MirageChunkKey> keys = new ArrayList<>();
        int fromX = MirageCapGeometry.firstChunkX(SLAB, WEST, DEPTH);
        int toX = MirageCapGeometry.lastChunkX(SLAB, WEST, DEPTH);
        for (int chunkX = fromX; chunkX <= toX; chunkX++) {
            for (int chunkZ = -HALF_WIDTH; chunkZ <= HALF_WIDTH; chunkZ++) {
                if (MirageCapGeometry.inCap(SLAB, WEST, 0, DEPTH, HALF_WIDTH, chunkX, chunkZ)) {
                    keys.add(new MirageChunkKey(SLAB, WEST, chunkX, chunkZ));
                }
            }
        }
        return keys;
    }

    /** Synthetic keys {@code depth} chunks past the seam, distinct along Z. */
    private static List<MirageChunkKey> keys(int depth, int count) {
        List<MirageChunkKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(new MirageChunkKey(SLAB, WEST, SEAM_CHUNK_X + depth, i));
        }
        return keys;
    }

    private static MirageChunkPayload payload(long seamId, int chunkZ) {
        byte side = (seamId & 1L) == 1L ? WEST : MirageChunkKey.EAST_BAND_INTO_WEST;
        MirageChunkKey key = new MirageChunkKey(SLAB, side, SEAM_CHUNK_X, chunkZ);
        return new MirageChunkPayload(key, 0, "twilight", "nether", 0, 0,
                new byte[0], new short[0], new int[0]);
    }

    private static long seamId(int slabX, byte side) {
        return ((long) slabX << 1) | (side == WEST ? 1L : 0L);
    }
}
