package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BandLaneAssignerTest {
    private static final byte[] PRODUCTION_KINDS = {
        BandLaneAssigner.SURFACE,
        BandLaneAssigner.UNDERGROUND,
        BandLaneAssigner.NETHER,
        BandLaneAssigner.END,
        BandLaneAssigner.AETHER,
        BandLaneAssigner.TWILIGHT,
        BandLaneAssigner.STARLIGHT,
        BandLaneAssigner.OTHERSIDE,
        BandLaneAssigner.VOIDSCAPE
    };

    @Test
    void fixedRegionsMatchNorthStar() {
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, 1L);
        int[] expected = {
            0, 0, 1, 0, 0, 1, 1, 2,
            0, 0, 1, 2, 2, 1, 0, 0,
            4, 5, 6, 7, 8, 0
        };
        for (int region = 0; region <= 21; region++) {
            assertEquals(expected[region], assigner.delegateIndex(region), "region " + region);
            assertEquals(expected[region], assigner.delegateIndex(-region), "region -" + region);
        }
        assertEquals(3, assigner.delegateIndex(32));
        assertEquals(3, assigner.delegateIndex(-32));
    }

    @Test
    void sameSeedIsDeterministic() {
        BandLaneAssigner a = new BandLaneAssigner(PRODUCTION_KINDS, 99L);
        BandLaneAssigner b = new BandLaneAssigner(PRODUCTION_KINDS, 99L);
        assertArrayEquals(range(a, -80, 80), range(b, -80, 80));
    }

    @Test
    void coverageAndAdjacencyHoldAcrossSeeds() {
        for (long seed = 0; seed < 256; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, seed);
            assertRandomAdjacentDistinct(assigner, 21, 80, seed);
            assertRandomAdjacentDistinct(assigner, -80, -21, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 22, 31, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, -31, -22, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 33, 48, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 49, 64, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 65, 80, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, -48, -33, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, -64, -49, seed);
            assertPool(assigner, 22, 31, BandLaneAssigner.pool(22), seed);
            assertPool(assigner, 33, 48, BandLaneAssigner.pool(33), seed);
        }
    }

    @Test
    void midWindowExcludesEnd() {
        for (long seed = 0; seed < 64; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, seed);
            for (int region = 22; region <= 31; region++) {
                assertNotEquals(3, assigner.delegateIndex(region), "end leaked into mid window seed " + seed);
            }
        }
    }

    @Test
    void missingOptionalModFallsBackAndStillCoversRemaining() {
        byte[] kinds = {
            BandLaneAssigner.SURFACE,
            BandLaneAssigner.UNDERGROUND,
            BandLaneAssigner.NETHER,
            BandLaneAssigner.END,
            BandLaneAssigner.TWILIGHT,
            BandLaneAssigner.STARLIGHT,
            BandLaneAssigner.OTHERSIDE,
            BandLaneAssigner.VOIDSCAPE
        };
        for (long seed = 0; seed < 64; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(kinds, seed);
            assertEquals(0, assigner.delegateIndex(16), "region 16 without aether seed " + seed);
            assertEquals(0, assigner.delegateIndex(-16), "region -16 without aether seed " + seed);
            assertEquals(4, assigner.delegateIndex(17), "twilight still present seed " + seed);
            assertRandomAdjacentDistinct(assigner, 21, 48, seed);
            assertWindowCoverage(assigner, kinds, 22, 31, seed);
            assertWindowCoverage(assigner, kinds, 33, 48, seed);
        }
    }

    @Test
    void positiveAndNegativeRandomSidesAreIndependent() {
        boolean differed = false;
        for (long seed = 0; seed < 256; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, seed);
            if (assigner.delegateIndex(22) != assigner.delegateIndex(-22)) {
                differed = true;
                break;
            }
        }
        assertTrue(differed, "signed-region mix should let +22 and -22 diverge on some seeds");
    }

    @Test
    void delegateIndexNeverNegative() {
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, 7L);
        for (int region = -80; region <= 80; region++) {
            int index = assigner.delegateIndex(region);
            assertTrue(index >= 0 && index < PRODUCTION_KINDS.length, "region " + region);
        }
    }

    private static int[] range(BandLaneAssigner assigner, int from, int to) {
        int[] out = new int[to - from + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = assigner.delegateIndex(from + i);
        }
        return out;
    }

    private static void assertRandomAdjacentDistinct(BandLaneAssigner assigner, int from, int to, long seed) {
        int previous = assigner.delegateIndex(from);
        for (int region = from + 1; region <= to; region++) {
            int current = assigner.delegateIndex(region);
            assertNotEquals(previous, current, "adjacent delegates matched at seed " + seed + " region " + region);
            previous = current;
        }
    }

    private static void assertWindowCoverage(BandLaneAssigner assigner, byte[] kinds, int from, int to, long seed) {
        int sample = Math.abs(from) >= Math.abs(to) ? from : to;
        byte[] wanted = BandLaneAssigner.pool(Math.abs(sample));
        boolean[] seen = new boolean[kinds.length];
        for (int region = from; region <= to; region++) {
            seen[assigner.delegateIndex(region)] = true;
        }
        for (int i = 0; i < kinds.length; i++) {
            if (!contains(wanted, kinds[i])) {
                continue;
            }
            assertTrue(seen[i], "missing delegate " + i + " in " + from + ".." + to + " seed " + seed);
        }
    }

    private static void assertPool(BandLaneAssigner assigner, int from, int to, byte[] wanted, long seed) {
        for (int region = from; region <= to; region++) {
            byte kind = PRODUCTION_KINDS[assigner.delegateIndex(region)];
            assertTrue(contains(wanted, kind), "out-of-pool kind " + kind + " at " + region + " seed " + seed);
        }
    }

    private static boolean contains(byte[] wanted, byte kind) {
        for (byte value : wanted) {
            if (value == kind) {
                return true;
            }
        }
        return false;
    }
}
