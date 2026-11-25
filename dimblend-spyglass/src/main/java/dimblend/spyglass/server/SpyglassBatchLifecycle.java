package dimblend.spyglass.server;

/**
 * What the server does with the busy flag once a batch's payload has been sent.
 *
 * <p>The spyglass shows a "scan in flight" indicator from {@code SpyglassBusyPayload}. It has to
 * cover the <em>whole</em> lifecycle of the player's request — worker running, {@code complete}
 * queued on the server thread, and any chained continuation batch — or the indicator blinks off
 * between two batches of one scan, which is exactly what a chained scan looks like when a world
 * is generating under the player.
 *
 * <p>Pure, so the rule is pinned by tests instead of by a running server: the flags are the
 * arguments, and the decision is a function of them.
 */
final class SpyglassBatchLifecycle {

    /** The one decision {@code complete} makes after sending a payload. */
    enum AfterBatch {
        /** The batch skipped chunks: enqueue one more at the player's current position, flag stays up. */
        CONTINUE_BATCH,
        /** Nothing is left to do for this player: lower the flag. */
        DROP_BUSY,
        /** A worker or a queued request already owns the slot: leave the flag raised for it. */
        KEEP_BUSY_FOR_QUEUED_REQUEST
    }

    /**
     * @param skippedChunks     chunks this batch could not finish ({@code StructurePredictor.Prediction})
     * @param scoping           the player's state still says "scoping"
     * @param playerStillScoping the live player still holds the spyglass to their eye
     * @param superseded        a newer request claimed a higher sequence while this batch ran
     * @param slotBusy          a worker is running or a request is queued behind this batch
     */
    static AfterBatch afterBatch(
            int skippedChunks,
            boolean scoping,
            boolean playerStillScoping,
            boolean superseded,
            boolean slotBusy) {
        if (slotBusy) {
            // Whoever owns the slot reports the flag; clearing it here would flicker.
            return AfterBatch.KEEP_BUSY_FOR_QUEUED_REQUEST;
        }
        if (skippedChunks <= 0) {
            return AfterBatch.DROP_BUSY;
        }
        if (!scoping || !playerStillScoping) {
            return AfterBatch.DROP_BUSY;
        }
        if (superseded) {
            // A newer sequence exists but nothing is running: its continuation claim failed, so
            // there is no batch left to chain onto.
            return AfterBatch.DROP_BUSY;
        }
        return AfterBatch.CONTINUE_BATCH;
    }

    private SpyglassBatchLifecycle() {
    }
}
