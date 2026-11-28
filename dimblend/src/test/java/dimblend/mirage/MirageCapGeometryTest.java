package dimblend.mirage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Cap geometry is shared by the client request planner, the server target set and the client
 * completeness check, so every number here is a contract between two endpoints. Both bugs it
 * pins: a fixed 224-block activation radius left the far band's wall popping in at render distance
 * 12, and a full-rectangle target set regenerated 5-10x more chunks than a thin outer cap needs.
 */
class MirageCapGeometryTest {
    private static final int SEAM = 4096;
    private static final byte WEST = MirageChunkKey.WEST_BAND_INTO_EAST;
    private static final byte EAST = MirageChunkKey.EAST_BAND_INTO_WEST;

    @Test
    void renderDistanceDrivesEveryRadius() {
        assertEquals(6, MirageCapGeometry.clampRenderDistanceChunks(2));
        assertEquals(16, MirageCapGeometry.clampRenderDistanceChunks(32));
        assertEquals(12, MirageCapGeometry.clampRenderDistanceChunks(0),
                "an unavailable option must fall back to the default, never to zero");

        assertEquals(224, MirageCapGeometry.seamActivationRadius(12), "12 * 16 + 32");
        assertEquals(272, MirageCapGeometry.requestRadius(12), "+48 blocks of lead time");
        assertEquals(320, MirageCapGeometry.dropRadius(12), "+48 blocks of hysteresis");
        assertTrue(MirageCapGeometry.dropRadius(12) > MirageCapGeometry.requestRadius(12)
                && MirageCapGeometry.requestRadius(12) > MirageCapGeometry.seamActivationRadius(12));
        assertTrue(MirageCapGeometry.seamActivationRadius(16) > MirageCapGeometry.seamActivationRadius(6),
                "a 16-chunk render distance must reach further than a 6-chunk one");
    }

    @Test
    void theCapGrowsMonotonicallyAsTheCameraApproaches() {
        MirageCapGeometry.CapShape previous = null;
        for (int distance = 400; distance >= 0; distance -= 4) {
            MirageCapGeometry.CapShape shape = MirageCapGeometry.capShape(distance, 12);
            assertTrue(shape.depthX() >= MirageCapGeometry.MIN_DEPTH_X
                    && shape.depthX() <= MirageCapGeometry.MAX_DEPTH_X, "depth in range at " + distance);
            assertTrue(shape.halfWidthZ() >= MirageCapGeometry.MIN_HALF_WIDTH_Z
                    && shape.halfWidthZ() <= MirageCapGeometry.MAX_HALF_WIDTH_Z,
                    "half width in range at " + distance);
            if (previous != null) {
                assertTrue(shape.depthX() >= previous.depthX(),
                        "depth must not shrink while approaching: " + distance);
                assertTrue(shape.halfWidthZ() >= previous.halfWidthZ(),
                        "chord must not shrink while approaching: " + distance);
            }
            previous = shape;
        }
        // At the seam: the full cap, and depth 12 means 192 blocks of tableau.
        MirageCapGeometry.CapShape atSeam = MirageCapGeometry.capShape(0.0, 12);
        assertEquals(12, atSeam.depthX());
        assertEquals(12, atSeam.halfWidthZ());

        // A walking speed of 5 m/s must not reshape more than once per 32 blocks:
        // [192, 224) is one 2-chunk bucket (distChunks = 12), [160, 192) is the next (10).
        assertEquals(MirageCapGeometry.capShape(220.0, 12), MirageCapGeometry.capShape(196.0, 12),
                "distance is quantised to two-chunk buckets");
        assertFalse(MirageCapGeometry.capShape(220.0, 12).equals(MirageCapGeometry.capShape(180.0, 12)),
                "crossing into the next 32-block bucket grows the cap");
    }

    @Test
    void aSmallerRenderDistanceShrinksTheCap() {
        int nearTotal = capCount(MirageCapGeometry.capShape(0.0, 12));
        int farTotal = capCount(MirageCapGeometry.capShape(0.0, 6));
        assertTrue(farTotal < nearTotal, "RD 6 must not pay for RD 12's tableau: " + farTotal);
    }

    private static int capCount(MirageCapGeometry.CapShape shape) {
        return countInCap(shape.depthX(), shape.halfWidthZ());
    }

    private static int countInCap(int depthX, int halfWidthZ) {
        int count = 0;
        for (int chunkX = MirageCapGeometry.firstChunkX(SEAM, WEST, depthX);
                chunkX <= MirageCapGeometry.lastChunkX(SEAM, WEST, depthX); chunkX++) {
            for (int chunkZ = -halfWidthZ; chunkZ <= halfWidthZ; chunkZ++) {
                if (MirageCapGeometry.inCap(SEAM, WEST, 0, depthX, halfWidthZ, chunkX, chunkZ)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void theEllipseTrimsOnlyTheInvisibleCorners() {
        int depth = MirageCapGeometry.MAX_DEPTH_X;
        int halfWidth = MirageCapGeometry.MAX_HALF_WIDTH_Z;
        int seamChunkX = SEAM >> 4;
        // Full width right on the seam: the two chunks on the seam row, both far corners.
        assertTrue(MirageCapGeometry.inCap(SEAM, WEST, 0, depth, halfWidth, seamChunkX, halfWidth));
        assertTrue(MirageCapGeometry.inCap(SEAM, WEST, 0, depth, halfWidth, seamChunkX, -halfWidth));
        // Full depth straight ahead.
        assertTrue(MirageCapGeometry.inCap(SEAM, WEST, 0, depth, halfWidth, seamChunkX + depth - 1, 0));
        // The deep corner is gone.
        assertFalse(MirageCapGeometry.inCap(SEAM, WEST, 0, depth, halfWidth,
                seamChunkX + depth - 1, halfWidth));
        // Nothing behind the seam, nothing outside the half width.
        assertFalse(MirageCapGeometry.inCap(SEAM, WEST, 0, depth, halfWidth, seamChunkX - 1, 0));
        assertFalse(MirageCapGeometry.inCap(SEAM, WEST, 0, depth, halfWidth, seamChunkX, halfWidth + 1));

        int inside = countInCap(depth, halfWidth);
        assertTrue(inside > 0 && inside < (depth * (2 * halfWidth + 1)),
                "the ellipse must trim strictly fewer corners than the rectangle: " + inside);
        assertTrue(inside <= 300, "a full cap stays a few hundred chunks, was " + inside);
        assertTrue(inside <= 768, "and well inside the per-seam server cache budget");
    }

    @Test
    void bothSidesMirrorAroundTheSeam() {
        int seamChunkX = SEAM >> 4;
        // side +1 fills east of the seam, side -1 fills west of it.
        assertEquals(seamChunkX, MirageCapGeometry.firstChunkX(SEAM, WEST, 4));
        assertEquals(seamChunkX + 3, MirageCapGeometry.lastChunkX(SEAM, WEST, 4));
        assertEquals(seamChunkX - 4, MirageCapGeometry.firstChunkX(SEAM, EAST, 4));
        assertEquals(seamChunkX - 1, MirageCapGeometry.lastChunkX(SEAM, EAST, 4));

        assertTrue(MirageCapGeometry.inCap(SEAM, WEST, 0, 4, 3, seamChunkX, 0));
        assertFalse(MirageCapGeometry.inCap(SEAM, EAST, 0, 4, 3, seamChunkX, 0),
                "east-side mirage must not ask for the band-B side of the seam");
        assertTrue(MirageCapGeometry.inCap(SEAM, EAST, 0, 4, 3, seamChunkX - 1, 0));
        // Band seams are always 16-aligned (bandSize is a multiple of 16), so the two sides meet
        // cleanly at seamChunkX - 1 | seamChunkX with identical span.
        assertEquals(MirageCapGeometry.firstChunkX(SEAM, WEST, 4),
                MirageCapGeometry.lastChunkX(SEAM, EAST, 4) + 1);
        assertEquals(MirageCapGeometry.lastChunkX(SEAM, WEST, 4) - MirageCapGeometry.firstChunkX(SEAM, WEST, 4),
                MirageCapGeometry.lastChunkX(SEAM, EAST, 4) - MirageCapGeometry.firstChunkX(SEAM, EAST, 4));
    }

    @Test
    void degenerateShapesAreSafe() {
        assertFalse(MirageCapGeometry.inCap(SEAM, WEST, 0, 0, 3, SEAM >> 4, 0));
        assertFalse(MirageCapGeometry.inCap(SEAM, WEST, 0, 3, -1, SEAM >> 4, 0));
        MirageCapGeometry.CapShape shape = MirageCapGeometry.capShape(Double.NaN, 12);
        assertTrue(shape.depthX() >= MirageCapGeometry.MIN_DEPTH_X,
                "a broken distance must not produce a zero cap");
    }
}
