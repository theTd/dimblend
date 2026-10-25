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
    /** The reveal distance of every snapshot in these tests; they are about the other triggers. */
    private static final double REVEAL = 173.0;

    @Test
    void firstScopingTickIsAlwaysDue() {
        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                null, null, 0.0, 0L, NO_FAILURE, Level.OVERWORLD, Vec3.ZERO, REVEAL, NOW));
    }

    @Test
    void smallMovementsAreNotDue() {
        Vec3 request = new Vec3(100.0, 64.0, 100.0);
        Vec3 moved = request.add(31.9, 0.0, 0.0);

        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                request, Level.OVERWORLD, REVEAL, NOW - 1_000L, NO_FAILURE, Level.OVERWORLD, moved,
                REVEAL, NOW));
    }

    @Test
    void movementAtTheThresholdIsDue() {
        Vec3 request = new Vec3(0.0, 64.0, 0.0);
        Vec3 moved = request.add(SpyglassRefreshPolicy.MOVE_THRESHOLD_BLOCKS, 0.0, 0.0);

        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                request, Level.OVERWORLD, REVEAL, NOW - 1_000L, NO_FAILURE, Level.OVERWORLD, moved,
                REVEAL, NOW));
    }

    @Test
    void dimensionChangeIsDue() {
        ResourceKey<Level> nether = Level.NETHER;

        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                Vec3.ZERO, Level.OVERWORLD, REVEAL, NOW - 1_000L, NO_FAILURE, nether, Vec3.ZERO,
                REVEAL, NOW));
    }

    @Test
    void revealDistanceChangeAtTheThresholdIsDue() {
        Vec3 position = new Vec3(5.0, 64.0, 5.0);

        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                position,
                Level.OVERWORLD,
                REVEAL,
                NOW - 1_000L,
                NO_FAILURE,
                Level.OVERWORLD,
                position,
                REVEAL + SpyglassRefreshPolicy.REVEAL_THRESHOLD_BLOCKS - 0.01,
                NOW));
        // A smaller reveal distance (the fog closing in) also changes which structures are eligible.
        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                position,
                Level.OVERWORLD,
                REVEAL,
                NOW - 1_000L,
                NO_FAILURE,
                Level.OVERWORLD,
                position,
                REVEAL - SpyglassRefreshPolicy.REVEAL_THRESHOLD_BLOCKS,
                NOW));
    }

    @Test
    void intervalRefreshIsDueAtThirtySeconds() {
        Vec3 position = new Vec3(5.0, 64.0, 5.0);

        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                position,
                Level.OVERWORLD,
                REVEAL,
                NOW - SpyglassRefreshPolicy.REFRESH_INTERVAL_NANOS + 1L,
                NO_FAILURE,
                Level.OVERWORLD,
                position,
                REVEAL,
                NOW));
        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                position,
                Level.OVERWORLD,
                REVEAL,
                NOW - SpyglassRefreshPolicy.REFRESH_INTERVAL_NANOS,
                NO_FAILURE,
                Level.OVERWORLD,
                position,
                REVEAL,
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
        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                position, Level.OVERWORLD, REVEAL, request, failure, Level.OVERWORLD, position,
                REVEAL, backoffDue));
        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                position, Level.OVERWORLD, REVEAL, request, failure, Level.OVERWORLD, position,
                REVEAL, backoffDue - 1L));
        // One second after the failure the player should not be re-scanned yet.
        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                position, Level.OVERWORLD, REVEAL, request, failure, Level.OVERWORLD, position,
                REVEAL, NOW));
    }

    @Test
    void aFailureOlderThanTheLastRequestDoesNotShortenTheInterval() {
        Vec3 position = new Vec3(5.0, 64.0, 5.0);
        long failure = NOW - 20_000_000_000L;
        // A later request succeeded, so only the regular interval applies to it.
        long request = NOW - 1_000_000_000L;

        assertFalse(SpyglassRefreshPolicy.isRefreshDue(
                position, Level.OVERWORLD, REVEAL, request, failure, Level.OVERWORLD, position,
                REVEAL, NOW));
        assertTrue(SpyglassRefreshPolicy.isRefreshDue(
                position,
                Level.OVERWORLD,
                REVEAL,
                request,
                failure,
                Level.OVERWORLD,
                position,
                REVEAL,
                request + SpyglassRefreshPolicy.REFRESH_INTERVAL_NANOS));
    }

    @Test
    void theRevealPaddingCoversTheRefreshThreshold() {
        assertTrue(SpyglassRefreshPolicy.SNAPSHOT_PAD_BLOCKS
                >= SpyglassRefreshPolicy.REVEAL_THRESHOLD_BLOCKS);
        assertTrue(SpyglassRefreshPolicy.SNAPSHOT_PAD_BLOCKS
                >= SpyglassRefreshPolicy.MOVE_THRESHOLD_BLOCKS);
    }
}
