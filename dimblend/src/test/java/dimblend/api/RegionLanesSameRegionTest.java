package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The region rule behind the photo-origin HUD. Exercises the pure form of
 * {@link RegionLanes#sameRegion(net.minecraft.world.level.Level,
 * net.minecraft.resources.ResourceLocation, int, int)}: level instances need a running game, while
 * every decision the rule makes is a function of these five values.
 */
class RegionLanesSameRegionTest {
    private static final int BAND = 1000;

    @Test
    void aDifferentDimensionIsNeverTheSameRegion() {
        assertFalse(RegionLanes.sameRegion(false, false, 0, 10, 10));
        assertFalse(RegionLanes.sameRegion(false, true, BAND, 10, 10));
    }

    @Test
    void aNonRotatingDimensionHasOneRegion() {
        assertTrue(RegionLanes.sameRegion(true, false, 0, -90_000, 90_000));
    }

    @Test
    void anUnknownBandSizeIsTreatedAsADifferentRegion() {
        assertFalse(RegionLanes.sameRegion(true, true, 0, 10, 10));
        assertFalse(RegionLanes.sameRegion(true, true, -1, 10, 10));
    }

    @Test
    void blockColumnsInsideOneBandShareTheRegion() {
        assertTrue(RegionLanes.sameRegion(true, true, BAND, 0, 999));
        assertTrue(RegionLanes.sameRegion(true, true, BAND, 1000, 1999));
        assertTrue(RegionLanes.sameRegion(true, true, BAND, -1, -1000));
    }

    @Test
    void bandBoundariesSeparateRegionsOnBothSides() {
        assertFalse(RegionLanes.sameRegion(true, true, BAND, 999, 1000));
        assertFalse(RegionLanes.sameRegion(true, true, BAND, -1, 0));
        assertFalse(RegionLanes.sameRegion(true, true, BAND, -1001, -1));
    }
}
