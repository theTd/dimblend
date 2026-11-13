package dimblend.purge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

class SableRegionPurgeMathTest {

    @Test
    void regionCoordSplitsCleanlyAt512BlockBoundariesIncludingNegatives() {
        assertEquals(0, SableRegionPurgeMath.regionCoordOfBlock(0.0D));
        assertEquals(0, SableRegionPurgeMath.regionCoordOfBlock(511.999D));
        assertEquals(1, SableRegionPurgeMath.regionCoordOfBlock(512.0D));
        assertEquals(-1, SableRegionPurgeMath.regionCoordOfBlock(-0.001D));
        assertEquals(-1, SableRegionPurgeMath.regionCoordOfBlock(-512.0D));
        assertEquals(-2, SableRegionPurgeMath.regionCoordOfBlock(-512.001D));
    }

    @Test
    void poseMembershipMatchesCanonicalRegionOnly() {
        assertTrue(SableRegionPurgeMath.isPoseInRegion(511.9D, 0.0D, 0, 0));
        assertFalse(SableRegionPurgeMath.isPoseInRegion(512.0D, 0.0D, 0, 0));
        assertTrue(SableRegionPurgeMath.isPoseInRegion(512.0D, -0.5D, 1, -1));
    }

    @Test
    void boundingBoxIntersectionHandlesStraddlingAndNonStraddlingBoxes() {
        // Box sits entirely in region (0, 0)
        assertTrue(SableRegionPurgeMath.doesBoxIntersectRegion(100.0D, 10.0D, 120.0D, 30.0D, 0, 0));
        assertFalse(SableRegionPurgeMath.doesBoxIntersectRegion(100.0D, 10.0D, 120.0D, 30.0D, 1, 0));

        // Box straddles x = 512.0 boundary between (0, 0) and (1, 0)
        assertTrue(SableRegionPurgeMath.doesBoxIntersectRegion(505.0D, -4.0D, 515.0D, 4.0D, 0, -1));
        assertTrue(SableRegionPurgeMath.doesBoxIntersectRegion(505.0D, -4.0D, 515.0D, 4.0D, 1, -1));
        assertFalse(SableRegionPurgeMath.doesBoxIntersectRegion(505.0D, -4.0D, 515.0D, 4.0D, 2, -1));
    }

    @Test
    void coupledChainDefersWhileAnyMemberPoseRegionStaysOnDiskThenPurgesAtomically() {
        long rMinus5 = RegionPurgePlanner.key(-5, 0);
        long rMinus4 = RegionPurgePlanner.key(-4, 0);
        long rMinus1 = RegionPurgePlanner.key(-1, 0);

        // Coupled pair straddles (-5, 0) and (-4, 0), held inside (-5, 0).
        LongArrayList chainPoses = LongArrayList.of(rMinus5, rMinus4);
        LongArrayList chainHolding = LongArrayList.of(rMinus5, rMinus5);

        // Purging (-5, 0) while (-4, 0) is still on disk must defer so dependency chain is never torn.
        LongOpenHashSet remainingAfterFirst = LongOpenHashSet.of(rMinus4, rMinus1);
        assertFalse(SableRegionPurgeMath.shouldPurgePoseChain(
                chainPoses, chainHolding, rMinus5, remainingAfterFirst));

        // Now (-4, 0) is also being purged (already removed from remaining set): both delete together!
        LongOpenHashSet remainingAfterSecond = LongOpenHashSet.of(rMinus1);
        assertTrue(SableRegionPurgeMath.shouldPurgePoseChain(
                chainPoses, chainHolding, rMinus4, remainingAfterSecond));
    }

    @Test
    void holdingChunkInPurgedRegionDoesNotDeleteSableWhosePoseIsInKeptRegion() {
        long keptRegion = RegionPurgePlanner.key(-1, 0);
        long purgedNeighbor = RegionPurgePlanner.key(-2, 0);

        LongArrayList chainPoses = LongArrayList.of(keptRegion);
        LongArrayList chainHolding = LongArrayList.of(purgedNeighbor);
        LongOpenHashSet remaining = LongOpenHashSet.of(keptRegion);

        assertFalse(SableRegionPurgeMath.shouldPurgePoseChain(
                chainPoses, chainHolding, purgedNeighbor, remaining));

        // Once the pose region itself is also absent on disk, the orphaned holding entry can be swept.
        LongOpenHashSet noRemaining = new LongOpenHashSet();
        assertTrue(SableRegionPurgeMath.shouldPurgePoseChain(
                chainPoses, chainHolding, purgedNeighbor, noRemaining));
    }

    @Test
    void isPointInExpandedBoxChecksMarginOnAllAxes() {
        assertTrue(SableRegionPurgeMath.isPointInExpandedBox(
                64.0, 65.0, 0.5,
                10.0, 65.0, -2.0,
                55.0, 66.0, 3.0,
                32.0));
        assertFalse(SableRegionPurgeMath.isPointInExpandedBox(
                4608.0, 120.0, 8.0,
                10.0, 65.0, -2.0,
                55.0, 66.0, 3.0,
                32.0));
    }
}
