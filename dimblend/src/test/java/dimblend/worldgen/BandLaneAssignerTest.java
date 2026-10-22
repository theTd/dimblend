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
            4, 5, 6, 7, 0
        };
        for (int region = 0; region <= 20; region++) {
            assertEquals(expected[region], assigner.delegateIndex(region), "region " + region);
            assertEquals(expected[region], assigner.delegateIndex(-region), "region -" + region);
        }
        assertEquals(3, assigner.delegateIndex(32));
        assertEquals(3, assigner.delegateIndex(-32));
        assertEquals(8, assigner.delegateIndex(33));
        assertEquals(8, assigner.delegateIndex(-33));
    }

    @Test
    void sameSeedIsDeterministic() {
        BandLaneAssigner a = new BandLaneAssigner(PRODUCTION_KINDS, 99L);
        BandLaneAssigner b = new BandLaneAssigner(PRODUCTION_KINDS, 99L);
        assertArrayEquals(range(a, -80, 80), range(b, -80, 80));
    }

    @Test
    void coverageAndAdjacencyHoldAcrossSeeds() {
        boolean sawSurfaceAt21 = false;
        boolean sawNonSurfaceAt21 = false;
        boolean sawLongSurfaceRun = false;
        for (long seed = 0; seed < 256; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, seed);
            assertSurfaceRunsAndDistinctOthers(assigner, PRODUCTION_KINDS, 21, 81, seed);
            assertSurfaceRunsAndDistinctOthers(assigner, PRODUCTION_KINDS, -81, -21, seed);
            assertFixedEndBoundariesDistinct(assigner, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 21, 31, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, -31, -21, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 34, 49, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 50, 65, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, 66, 81, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, -49, -34, seed);
            assertWindowCoverage(assigner, PRODUCTION_KINDS, -65, -50, seed);
            assertPool(assigner, 21, 31, BandLaneAssigner.pool(21), seed);
            assertPool(assigner, 34, 49, BandLaneAssigner.pool(34), seed);
            if (PRODUCTION_KINDS[assigner.delegateIndex(21)] == BandLaneAssigner.SURFACE) {
                sawSurfaceAt21 = true;
            } else {
                sawNonSurfaceAt21 = true;
            }
            if (hasSurfaceRunAtLeast(assigner, PRODUCTION_KINDS, 19, 81, 3)
                    || hasSurfaceRunAtLeast(assigner, PRODUCTION_KINDS, -81, -19, 3)) {
                sawLongSurfaceRun = true;
            }
        }
        assertTrue(sawSurfaceAt21, "expected some seed to attach region 21 to fixed surface 20");
        assertTrue(sawNonSurfaceAt21, "expected some seed to leave region 21 non-surface");
        assertTrue(sawLongSurfaceRun, "expected a surface run of length >= 3");
    }

    @Test
    void midWindowExcludesEndAndVoidscape() {
        for (long seed = 0; seed < 64; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, seed);
            for (int region = 21; region <= 31; region++) {
                int kind = PRODUCTION_KINDS[assigner.delegateIndex(region)];
                assertNotEquals(BandLaneAssigner.END, kind, "end leaked into mid window seed " + seed);
                assertNotEquals(BandLaneAssigner.VOIDSCAPE, kind, "voidscape leaked into mid window seed " + seed);
            }
        }
    }

    @Test
    void scriptedOutwardFollowsFixedScriptOnBothSides() {
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, 7L);
        // 0 → 1（固定地表）；19 → 20（固定地表）；31 → 32（固定末地，delegate 3）；32 → 33（固定虚空，delegate 8）
        assertEquals(assigner.delegateIndex(1), assigner.scriptedOutwardDelegate(0));
        assertEquals(assigner.delegateIndex(20), assigner.scriptedOutwardDelegate(19));
        assertEquals(3, assigner.scriptedOutwardDelegate(31));
        assertEquals(8, assigner.scriptedOutwardDelegate(32));
        // 负侧镜像同一剧本：-19 → -20；-31 → -32；-32 → -33
        assertEquals(assigner.delegateIndex(-20), assigner.scriptedOutwardDelegate(-19));
        assertEquals(3, assigner.scriptedOutwardDelegate(-31));
        assertEquals(8, assigner.scriptedOutwardDelegate(-32));
    }

    @Test
    void scriptedOutwardIsMinusOneForRandomDraws() {
        // 固定地表 20 向外不强制连段（附着只是可选项），21 为随机抽选 → -1
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, 7L);
        assertEquals(-1, assigner.scriptedOutwardDelegate(20));
        assertEquals(-1, assigner.scriptedOutwardDelegate(-20));
    }

    @Test
    void scriptedOutwardFollowsFreshSurfaceRunsAcrossSeeds() {
        for (long seed = 0; seed < 64; seed++) {
            assertFreshSurfaceRunsCloseOutward(new BandLaneAssigner(PRODUCTION_KINDS, seed), 21, 30, seed);
            assertFreshSurfaceRunsCloseOutward(new BandLaneAssigner(PRODUCTION_KINDS, seed), -21, -30, seed);
        }
    }

    /** 正侧 from=21,to=30；负侧 from=-21,to=-30。开段格 = 本地表且靠出生点侧邻居非地表。 */
    private static void assertFreshSurfaceRunsCloseOutward(BandLaneAssigner assigner, int from, int to, long seed) {
        int step = from < 0 ? -1 : 1;
        for (int region = from; ; region += step) {
            int here = assigner.delegateIndex(region);
            int inward = assigner.delegateIndex(region - step);
            int outward = assigner.scriptedOutwardDelegate(region);
            if (PRODUCTION_KINDS[here] == BandLaneAssigner.SURFACE
                    && PRODUCTION_KINDS[inward] != BandLaneAssigner.SURFACE) {
                // 刚开的地表连段第一格：向外一格被强制收尾为同一地表 delegate
                assertEquals(here, outward, "seed " + seed + " region " + region);
            } else {
                // 其余随机格（含连段延续格）向外都是抽选 → -1
                assertEquals(-1, outward, "seed " + seed + " region " + region);
            }
            if (region == to) {
                return;
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
            assertSurfaceRunsAndDistinctOthers(assigner, kinds, 21, 48, seed);
            assertWindowCoverage(assigner, kinds, 21, 31, seed);
            assertWindowCoverage(assigner, kinds, 34, 49, seed);
        }
    }

    @Test
    void modDelegateExcludedFromMidButCoveredInFar() {
        byte[] kinds = {
            BandLaneAssigner.SURFACE,
            BandLaneAssigner.UNDERGROUND,
            BandLaneAssigner.NETHER,
            BandLaneAssigner.END,
            BandLaneAssigner.AETHER,
            BandLaneAssigner.TWILIGHT,
            BandLaneAssigner.STARLIGHT,
            BandLaneAssigner.OTHERSIDE,
            BandLaneAssigner.VOIDSCAPE,
            BandLaneAssigner.MOD
        };
        for (byte kind : BandLaneAssigner.pool(21)) {
            assertNotEquals(BandLaneAssigner.MOD, kind, "MOD must stay out of the mid pool");
        }
        for (long seed = 0; seed < 64; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(kinds, seed);
            for (int region = 21; region <= 31; region++) {
                assertNotEquals(
                        BandLaneAssigner.MOD, kinds[assigner.delegateIndex(region)],
                        "MOD leaked into mid window seed " + seed);
            }
            assertWindowCoverage(assigner, kinds, 34, 49, seed);
            assertSurfaceRunsAndDistinctOthers(assigner, kinds, 21, 49, seed);
        }
    }

    @Test
    void positiveAndNegativeRandomSidesAreIndependent() {
        boolean differed = false;
        for (long seed = 0; seed < 256; seed++) {
            BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, seed);
            if (assigner.delegateIndex(21) != assigner.delegateIndex(-21)) {
                differed = true;
                break;
            }
        }
        assertTrue(differed, "signed-region mix should let +21 and -21 diverge on some seeds");
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

    private static void assertFixedEndBoundariesDistinct(BandLaneAssigner assigner, long seed) {
        int[][] boundaries = {{31, 32}, {33, 34}};
        for (int[] boundary : boundaries) {
            assertNotEquals(
                    assigner.delegateIndex(boundary[0]), assigner.delegateIndex(boundary[1]),
                    "fixed/random boundary matched at seed " + seed + " region " + boundary[1]);
            assertNotEquals(
                    assigner.delegateIndex(-boundary[0]), assigner.delegateIndex(-boundary[1]),
                    "fixed/random boundary matched at seed " + seed + " region -" + boundary[1]);
        }
    }

    private static void assertSurfaceRunsAndDistinctOthers(
            BandLaneAssigner assigner, byte[] kinds, int from, int to, long seed) {
        for (int region = from; region <= to; region++) {
            if (BandLaneAssigner.fixedKind(Math.abs(region)) >= 0) {
                continue;
            }
            int index = assigner.delegateIndex(region);
            int left = assigner.delegateIndex(region - 1);
            int right = assigner.delegateIndex(region + 1);
            if (kinds[index] == BandLaneAssigner.SURFACE) {
                boolean besideSurface = kinds[left] == BandLaneAssigner.SURFACE
                        || kinds[right] == BandLaneAssigner.SURFACE;
                assertTrue(besideSurface, "isolated surface at seed " + seed + " region " + region);
            } else {
                assertNotEquals(index, left, "adjacent delegates matched at seed " + seed + " region " + region);
                assertNotEquals(index, right, "adjacent delegates matched at seed " + seed + " region " + region);
            }
        }
    }

    private static boolean hasSurfaceRunAtLeast(
            BandLaneAssigner assigner, byte[] kinds, int from, int to, int length) {
        int run = 0;
        for (int region = from; region <= to; region++) {
            if (kinds[assigner.delegateIndex(region)] == BandLaneAssigner.SURFACE) {
                run++;
                if (run >= length) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
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
