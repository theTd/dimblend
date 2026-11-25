package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dimblend.spyglass.server.SpyglassBatchLifecycle.AfterBatch;
import org.junit.jupiter.api.Test;

/**
 * The busy-indicator lifecycle across a chained scan.
 *
 * <p>The client shows "scanning" while the busy flag is up. One requested scan may take several
 * batches, and the flag has to stay up across the handoffs: while a queued request or a running
 * worker owns the slot, and between a batch that skipped chunks and its continuation. It must
 * come down exactly once, when the last batch skipped nothing.
 */
class SpyglassBatchLifecycleTest {

    @Test
    void aCleanBatchIsTheEndOfTheScan() {
        assertEquals(AfterBatch.DROP_BUSY, SpyglassBatchLifecycle.afterBatch(
                0, true, true, false, false));
    }

    @Test
    void aBatchThatSkippedChunksChainsWhileThePlayerStillLooks() {
        assertEquals(AfterBatch.CONTINUE_BATCH, SpyglassBatchLifecycle.afterBatch(
                7, true, true, false, false));
    }

    @Test
    void aQueuedRequestKeepsTheFlagUpWhateverTheBatchFound() {
        assertEquals(AfterBatch.KEEP_BUSY_FOR_QUEUED_REQUEST, SpyglassBatchLifecycle.afterBatch(
                0, true, true, false, true));
        assertEquals(AfterBatch.KEEP_BUSY_FOR_QUEUED_REQUEST, SpyglassBatchLifecycle.afterBatch(
                7, true, true, true, true));
    }

    @Test
    void droppingTheSpyglassEndsTheChain() {
        assertEquals(AfterBatch.DROP_BUSY, SpyglassBatchLifecycle.afterBatch(
                7, false, true, false, false));
        assertEquals(AfterBatch.DROP_BUSY, SpyglassBatchLifecycle.afterBatch(
                7, true, false, false, false));
    }

    @Test
    void aSupersededBatchWithoutASlotToReportToEnds() {
        // A newer sequence was claimed but its request never got dispatched; nothing will report
        // the flag for it, so this batch has to lower it.
        assertEquals(AfterBatch.DROP_BUSY, SpyglassBatchLifecycle.afterBatch(
                7, true, true, true, false));
    }

    @Test
    void skippedChunksAloneNeverLowersTheFlag() {
        // The whole point: "skipped > 0" is a reason to keep going, never a reason to look idle.
        for (int skipped = 1; skipped <= 5; skipped++) {
            assertEquals(AfterBatch.CONTINUE_BATCH, SpyglassBatchLifecycle.afterBatch(
                    skipped, true, true, false, false));
        }
    }
}
