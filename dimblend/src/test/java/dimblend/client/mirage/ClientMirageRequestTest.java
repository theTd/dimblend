package dimblend.client.mirage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.mirage.MirageCapGeometry;
import dimblend.mirage.MirageChunkKey;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Request planning: the two bugs the CASTAWAYL capture measured were a seam 2030 blocks outside
 * the request window being rebuilt every tick, and a complete rectangle being re-requested every
 * five seconds. A third one showed up in the streaming pass: the stale retry kept firing while
 * the server was still generating mini-batches, and each retry made the server restart its queue.
 * All three are decided by pure functions, so they are pinned here.
 */
class ClientMirageRequestTest {
    private static final int BAND = 2048;

    @Test
    void farSeamOutsideTheRequestRadiusIsNotACandidate() {
        // floorDiv(cameraX - radius, bandSize) = 1 at cameraX 4078, but slabX 2048 is 2030 blocks
        // away: requesting it costs a packet per tick and is evicted again immediately.
        assertArrayEquals(new int[]{4096}, MirageRequestPlanner.candidateSlabs(4078.0, BAND, 112));
        assertArrayEquals(new int[]{-4096}, MirageRequestPlanner.candidateSlabs(-4078.0, BAND, 112));
    }

    @Test
    void candidateWindowIsSymmetricAndInclusiveAtTheEdge() {
        // Radius 2100 from 2000 reaches 4096 (2096 away) and 0 (2000 away), not -2048 (4048 away).
        assertArrayEquals(new int[]{0, 2048, 4096},
                MirageRequestPlanner.candidateSlabs(2000.0, BAND, 2100));
        // Exactly on the radius: still a candidate (2048 ± 2048 touches both neighbours).
        assertArrayEquals(new int[]{0, 2048, 4096},
                MirageRequestPlanner.candidateSlabs(2048.0, BAND, 2048));
        // Camera exactly on a seam finds it without needing either neighbour.
        assertArrayEquals(new int[]{4096}, MirageRequestPlanner.candidateSlabs(4096.0, BAND, 112));
    }

    @Test
    void degenerateBandSizeYieldsNoCandidates() {
        assertEquals(0, MirageRequestPlanner.candidateSlabs(1000.0, 0, 112).length);
        assertEquals(0, MirageRequestPlanner.candidateSlabs(1000.0, -2048, 112).length);
    }

    @Test
    void rectangleMatchesTheServerLayout() {
        // side +1 (west band into east columns): seam chunk .. seam + depth - 1.
        assertEquals(256, MirageRequestPlanner.fromChunkX(4096, MirageChunkKey.WEST_BAND_INTO_EAST, 8));
        assertEquals(263, MirageRequestPlanner.toChunkX(4096, MirageChunkKey.WEST_BAND_INTO_EAST, 8));
        // side -1: seam - depth .. seam - 1.
        assertEquals(248, MirageRequestPlanner.fromChunkX(4096, MirageChunkKey.EAST_BAND_INTO_WEST, 8));
        assertEquals(255, MirageRequestPlanner.toChunkX(4096, MirageChunkKey.EAST_BAND_INTO_WEST, 8));
        assertEquals(128.0F, MirageRequestPlanner.depthBlocks(8), 1.0E-6F,
                "depth 8 is 128 blocks of tableau; the server cap is MAX_DEPTH_X = 12");
    }

    @Test
    void aWholeRectangleSuppressesTheResendButAMissingChunkDoesNot() {
        Set<Long> present = new HashSet<>();
        for (int chunkX = 256; chunkX <= 263; chunkX++) {
            for (int chunkZ = 1; chunkZ <= 9; chunkZ++) {
                present.add(key(chunkX, chunkZ));
            }
        }
        MirageRequestPlanner.Presence presence = (chunkX, chunkZ) -> present.contains(key(chunkX, chunkZ));
        assertTrue(MirageRequestPlanner.sideComplete(
                        4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4, presence),
                "8 x 9 chunks all present");
        assertTrue(MirageRequestPlanner.suppressesResend(true, false, false, true),
                "a complete rectangle must swallow the stale resend");

        assertFalse(MirageRequestPlanner.suppressesResend(true, false, false, false),
                "a genuinely missing chunk keeps the retry alive");
        assertFalse(MirageRequestPlanner.suppressesResend(true, true, false, true),
                "a moved centre changes the rectangle, so it is re-requested");
        assertFalse(MirageRequestPlanner.suppressesResend(true, false, true, true),
                "a changed shape changes the rectangle, so it is re-requested");
        assertFalse(MirageRequestPlanner.suppressesResend(false, false, false, false),
                "the first request always goes out");

        present.remove(key(260, 5));
        assertFalse(MirageRequestPlanner.sideComplete(
                        4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4, presence),
                "one missing chunk must not be mistaken for a complete rectangle");
    }

    /**
     * The completeness check must use the same elliptical predicate as the server, otherwise a
     * trimmed corner that was never requested keeps the resend timer alive forever.
     */
    @Test
    void trimmedCornersAreNotCountedAsMissingChunks() {
        Set<Long> inCap = new HashSet<>();
        for (int chunkX = 256; chunkX <= 263; chunkX++) {
            for (int chunkZ = 1; chunkZ <= 9; chunkZ++) {
                if (MirageCapGeometry.inCap(4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4, chunkX, chunkZ)) {
                    inCap.add(key(chunkX, chunkZ));
                }
            }
        }
        MirageRequestPlanner.Presence presence = (chunkX, chunkZ) -> inCap.contains(key(chunkX, chunkZ));
        assertTrue(MirageRequestPlanner.sideComplete(
                        4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4, presence),
                "every in-cap chunk is present: the cap is whole even without the corners");
        assertFalse(MirageCapGeometry.inCap(4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4, 263, 9),
                "precondition: (263,9) is a trimmed corner");
        assertFalse(MirageRequestPlanner.sideComplete(
                        4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4,
                        (chunkX, chunkZ) -> inCap.contains(key(chunkX, chunkZ))
                                && !(chunkX == 262 && chunkZ == 5)),
                "(262,5) is inside the ellipse: missing it is a genuine miss");

        // The old full-rectangle set is a strict superset, so it would still be "complete", but a
        // corner-only payload (the trimmed ones present, the centre missing) must not be.
        assertFalse(MirageRequestPlanner.sideComplete(
                        4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4,
                        (chunkX, chunkZ) -> MirageCapGeometry.inCap(
                                4096, MirageChunkKey.WEST_BAND_INTO_EAST, 5, 8, 4, chunkX, chunkZ)
                                && chunkZ != 5),
                "the seam row is required: that is what the player actually looks through");
    }

    /**
     * The capture showed the client re-requesting every {@code RESEND_TICKS} while the server was
     * still streaming mini-batches, and every such request made the server restart its queue. A
     * stale timer must therefore stay quiet while chunks are arriving — and nothing else may.
     */
    @Test
    void activeStreamingSwallowsTheStaleRetryAndNothingElse() {
        assertTrue(MirageRequestPlanner.suppressesStale(true, false, false, true, true),
                "chunks are still arriving: the stale resend must not restart the server queue");
        assertFalse(MirageRequestPlanner.suppressesStale(true, false, false, true, false),
                "a silent server still gets its retry");
        assertFalse(MirageRequestPlanner.suppressesStale(true, false, false, false, true),
                "no stale timer, nothing to suppress");
        assertFalse(MirageRequestPlanner.suppressesStale(false, false, false, true, true),
                "the first request always goes out");
        assertFalse(MirageRequestPlanner.suppressesStale(true, true, false, true, true),
                "a moved centre always goes out");
        assertFalse(MirageRequestPlanner.suppressesStale(true, false, true, true, true),
                "a grown cap always goes out");
    }

    @Test
    void streamActiveExpiresThreeSecondsAfterTheLastChunk() {
        assertEquals(60, MirageRequestPlanner.STREAM_ACTIVE_TICKS);
        assertFalse(MirageRequestPlanner.streamActive(0L, 500L), "nothing received yet");
        assertTrue(MirageRequestPlanner.streamActive(100L, 159L), "59 ticks later: still streaming");
        assertFalse(MirageRequestPlanner.streamActive(100L, 160L), "60 ticks later: silent again");
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
