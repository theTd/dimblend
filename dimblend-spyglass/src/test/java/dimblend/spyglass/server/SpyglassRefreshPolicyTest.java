package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SpyglassRefreshPolicyTest {
    private static final long NOW = 1_000_000_000_000L;
    /** No scan has failed since the request was submitted. */
    private static final long NO_FAILURE = 0L;
    /** The overworld has a single region, so these cases all sit in region 0. */
    private static final int SINGLE_REGION = 0;

    /** Same-region call with the common case's region numbers, so the other rules stay isolated. */
    private static boolean due(
            Vec3 requestPosition,
            ResourceKey<Level> requestDimension,
            long requestNanos,
            long failureNanos,
            ResourceKey<Level> dimension,
            Vec3 position,
            long nowNanos) {
        return SpyglassRefreshPolicy.isRefreshDue(
                requestPosition,
                requestDimension,
                SINGLE_REGION,
                requestNanos,
                failureNanos,
                dimension,
                SINGLE_REGION,
                position,
                nowNanos);
    }

    @Test
    void firstScopingTickIsAlwaysDue() {
        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                null, null, null, 0L, NO_FAILURE, Level.OVERWORLD, SINGLE_REGION, Vec3.ZERO, NOW));
    }

    @Test
    void smallMovementsAreNotDue() {
        Vec3 request = new Vec3(100.0, 64.0, 100.0);
        Vec3 moved = request.add(31.9, 0.0, 0.0);

        assertFalse(due(request, Level.OVERWORLD, NOW - 1_000L, NO_FAILURE, Level.OVERWORLD, moved, NOW));
    }

    @Test
    void movementAtTheThresholdIsDue() {
        Vec3 request = new Vec3(0.0, 64.0, 0.0);
        Vec3 moved = request.add(SpyglassRefreshPolicy.MOVE_THRESHOLD_BLOCKS, 0.0, 0.0);

        assertTrue(due(request, Level.OVERWORLD, NOW - 1_000L, NO_FAILURE, Level.OVERWORLD, moved, NOW));
    }

    @Test
    void dimensionChangeIsDue() {
        ResourceKey<Level> nether = Level.NETHER;

        assertTrue(due(Vec3.ZERO, Level.OVERWORLD, NOW - 1_000L, NO_FAILURE, nether, Vec3.ZERO, NOW));
    }

    @Test
    void intervalRefreshIsDueAtThirtySeconds() {
        Vec3 position = new Vec3(5.0, 64.0, 5.0);

        assertFalse(due(
                position,
                Level.OVERWORLD,
                NOW - SpyglassRefreshPolicy.REFRESH_INTERVAL_NANOS + 1L,
                NO_FAILURE,
                Level.OVERWORLD,
                position,
                NOW));
        assertTrue(due(
                position,
                Level.OVERWORLD,
                NOW - SpyglassRefreshPolicy.REFRESH_INTERVAL_NANOS,
                NO_FAILURE,
                Level.OVERWORLD,
                position,
                NOW));
    }

    @Test
    void aFailedScanIsDueAgainAfterTheBackoff() {
        Vec3 position = new Vec3(5.0, 64.0, 5.0);
        // The request was submitted, then failed a millisecond later; the full interval has not
        // elapsed, but the failure shortens the wait to the backoff.
        long request = NOW - 1_001_000_000L;
        long failure = NOW - 1_000_000_000L;

        long backoffDue = failure + SpyglassRefreshPolicy.FAILURE_BACKOFF_NANOS;
        assertTrue(due(position, Level.OVERWORLD, request, failure, Level.OVERWORLD, position, backoffDue));
        assertFalse(due(position, Level.OVERWORLD, request, failure, Level.OVERWORLD, position, backoffDue - 1L));
        // One second after the failure the player should not be re-scanned yet.
        assertFalse(due(position, Level.OVERWORLD, request, failure, Level.OVERWORLD, position, NOW));
    }

    @Test
    void aFailureOlderThanTheLastRequestDoesNotShortenTheInterval() {
        Vec3 position = new Vec3(5.0, 64.0, 5.0);
        long failure = NOW - 20_000_000_000L;
        // A later request succeeded, so only the regular interval applies to it.
        long request = NOW - 1_000_000_000L;

        assertFalse(due(position, Level.OVERWORLD, request, failure, Level.OVERWORLD, position, NOW));
        assertTrue(due(
                position,
                Level.OVERWORLD,
                request,
                failure,
                Level.OVERWORLD,
                position,
                request + SpyglassRefreshPolicy.REFRESH_INTERVAL_NANOS));
    }

    @Test
    void aRegionChangeIsDueImmediately() {
        // A border can be stepped over by one block: neither the 32-block threshold nor the 30 s
        // interval may hold the snapshot back, or the ring keeps describing the lane behind the player.
        Vec3 position = new Vec3(2047.5, 64.0, 5.0);

        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                position,
                Level.OVERWORLD,
                0,
                NOW - 1_000L,
                NO_FAILURE,
                Level.OVERWORLD,
                1,
                position,
                NOW));
        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                position,
                Level.OVERWORLD,
                0,
                NOW - 1_000L,
                NO_FAILURE,
                Level.OVERWORLD,
                0,
                position,
                NOW));
    }

    @Test
    void anUnknownPreviousRegionFallsBackToTheOtherRules() {
        Vec3 request = new Vec3(100.0, 64.0, 100.0);

        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                request,
                Level.OVERWORLD,
                null,
                NOW - 1_000L,
                NO_FAILURE,
                Level.OVERWORLD,
                7,
                request.add(1.0, 0.0, 0.0),
                NOW));
    }

    @Test
    void theSnapshotPaddingCoversTheMovementThreshold() {
        assertTrue(SpyglassRefreshPolicy.SNAPSHOT_PAD_BLOCKS
                >= SpyglassRefreshPolicy.MOVE_THRESHOLD_BLOCKS);
    }
}
