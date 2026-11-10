package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Boundary/seed regressions behind RegionLanes, independent of a Minecraft world. */
class SupplementSurfaceLaneMathTest {
    // Deliberately shuffled: a surface lane is an identity, never "delegate index 0".
    private static final byte[] KINDS = {
            BandLaneAssigner.NETHER, BandLaneAssigner.END, BandLaneAssigner.SURFACE,
            BandLaneAssigner.UNDERGROUND, BandLaneAssigner.AETHER, BandLaneAssigner.TWILIGHT,
            BandLaneAssigner.STARLIGHT, BandLaneAssigner.OTHERSIDE, BandLaneAssigner.VOIDSCAPE
    };

    @Test
    void exactPositiveAndNegativeEdgesUseSignedRegionsRatherThanAbsoluteX() {
        var lanes = new BandLaneAssigner(KINDS, 17);
        int width = 2048;
        assertTrue(surface(lanes, 2 * width - 1, width)); // region 1
        assertFalse(surface(lanes, 2 * width, width));   // region 2, underground
        assertFalse(surface(lanes, -width - 1, width)); // region -2, underground
        assertTrue(surface(lanes, -width, width));      // region -1
        assertTrue(surface(lanes, -1, width));
        assertTrue(surface(lanes, 0, width));
        assertFalse(surface(lanes, 7 * width, width));  // nether
        assertFalse(surface(lanes, 32 * width, width)); // end
        assertFalse(surface(lanes, 33 * width, width)); // voidscape
    }

    @Test
    void configurableBandWidthDoesNotBecomeAHardcodedSafeRadius() {
        var lanes = new BandLaneAssigner(KINDS, 17);
        for (int width : new int[] {64, 1024, 2048, 4096}) {
            assertEquals(1, BandLayout.regionOfBlockX(2 * width - 1, width));
            assertEquals(-2, BandLayout.regionOfBlockX(-width - 1, width));
            assertTrue(surface(lanes, 14 * width, width));
            assertFalse(surface(lanes, 13 * width, width));
        }
    }

    @Test
    void randomRegionsAreResolvedFromTheWorldSeedOnBothSides() {
        boolean sawSurface = false;
        boolean sawOther = false;
        for (long seed = 0; seed < 64; seed++) {
            var lanes = new BandLaneAssigner(KINDS, seed);
            for (int sign : new int[] {-1, 1}) {
                int region = 21 * sign;
                int blockX = region * 2048 + 1024;
                boolean actual = surface(lanes, blockX, 2048);
                sawSurface |= actual;
                sawOther |= !actual;
            }
        }
        assertTrue(sawSurface && sawOther, "the same far-region coordinate must not use a fixed safe-zone table");
    }

    private static boolean surface(BandLaneAssigner lanes, int blockX, int width) {
        return KINDS[lanes.delegateIndex(BandLayout.regionOfBlockX(blockX, width))] == BandLaneAssigner.SURFACE;
    }
}
