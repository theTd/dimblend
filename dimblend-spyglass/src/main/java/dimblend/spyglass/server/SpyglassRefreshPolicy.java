package dimblend.spyglass.server;

import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Pure refresh decision for the server-side spyglass scans, extracted so it can be tested without a
 * running server.
 *
 * <p>The server re-scans when the player starts scoping, changes dimension, moved at least
 * {@link #MOVE_THRESHOLD_BLOCKS} since the snapshot was built, or when
 * {@link #REFRESH_INTERVAL_NANOS} elapsed (to pick up chunks that were saved since). A scan that
 * failed after its request was submitted is retried after {@link #FAILURE_BACKOFF_NANOS} instead of
 * the full interval, so a transient failure does not leave the player without a snapshot for half a
 * minute. Because a snapshot is reused until then, it has to cover
 * {@link #SNAPSHOT_PAD_BLOCKS} beyond the exact display ring: the client filters the same snapshot
 * against its live position every frame, and the player can only be
 * {@code MOVE_THRESHOLD_BLOCKS} away from the state the ring was built for.
 */
final class SpyglassRefreshPolicy {
    static final int POLL_INTERVAL_TICKS = 10;
    static final double MOVE_THRESHOLD_BLOCKS = 32.0;
    /**
     * The server keeps this much slack on both ends of the snapshot ring. Must stay at least
     * {@link #MOVE_THRESHOLD_BLOCKS}: see the class comment.
     */
    static final double SNAPSHOT_PAD_BLOCKS = 48.0;
    static final long REFRESH_INTERVAL_NANOS = 30_000_000_000L;
    /** Retry delay after a scan failed; short enough to recover on the next few polls. */
    static final long FAILURE_BACKOFF_NANOS = 5_000_000_000L;
    static final long WARNING_INTERVAL_NANOS = 60_000_000_000L;

    private SpyglassRefreshPolicy() {
    }

    /**
     * @param failureNanos {@code System.nanoTime()} of the last failed scan, or {@code 0} when no
     *                     scan failed since the request was submitted
     */
    static boolean isRefreshDue(
            @Nullable Vec3 requestPosition,
            @Nullable ResourceKey<Level> requestDimension,
            long requestNanos,
            long failureNanos,
            ResourceKey<Level> dimension,
            Vec3 position,
            long nowNanos) {
        if (requestPosition == null || requestDimension == null) {
            return true;
        }
        if (!requestDimension.location().equals(dimension.location())) {
            return true;
        }
        if (requestPosition.distanceToSqr(position) >= MOVE_THRESHOLD_BLOCKS * MOVE_THRESHOLD_BLOCKS) {
            return true;
        }
        if (failureNanos > requestNanos) {
            // The request failed after it was submitted: retry after the backoff, not the interval.
            return nowNanos - failureNanos >= FAILURE_BACKOFF_NANOS;
        }
        return nowNanos - requestNanos >= REFRESH_INTERVAL_NANOS;
    }
}
