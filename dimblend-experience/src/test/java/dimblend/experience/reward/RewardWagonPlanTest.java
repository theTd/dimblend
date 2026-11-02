package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** Where each region's wagons go, and how many. */
class RewardWagonPlanTest {
    private static final int BAND = 2048;
    private static final long SEED = 0x5EED_CAFE_D00DL;
    private static final IntPredicate NOTHING_RESERVED = x -> false;

    private static List<RewardWagonPlan.Wagon> band(int index, String lane) {
        return RewardWagonPlan.forBand(SEED, index, BAND, lane, NOTHING_RESERVED);
    }

    @Test
    void theSameRegionAlwaysGivesTheSameWagons() {
        for (int index : new int[] {0, 1, 5, -3, 17}) {
            var first = band(index, "surface");
            var second = band(index, "surface");
            assertEquals(first, second, "band " + index);
        }
    }

    @Test
    void differentRegionsDiffer() {
        Set<Integer> centres = new HashSet<>();
        for (int index = 0; index < 40; index++) {
            // Any band with a wagon contributes its centre; collisions would mean the
            // regions are not actually independent.
            band(index, "underground").forEach(wagon -> centres.add(wagon.centerX()));
        }
        assertTrue(centres.size() > 20, "40 regions gave only " + centres.size() + " distinct centres");
    }

    @Test
    void emptyLanesNeverGetAWagon() {
        for (String lane : new String[] {"end", "voidscape", "mod", "aether_extended", "otherside_mod"}) {
            for (int index = 0; index < 20; index++) {
                assertTrue(band(index, lane).isEmpty(), lane + " band " + index);
            }
        }
    }

    @Test
    void netherAndAetherAlwaysGetExactlyOne() {
        for (String lane : new String[] {"nether", "aether"}) {
            for (int index = -20; index < 20; index++) {
                assertEquals(1, band(index, lane).size(), lane + " band " + index);
            }
        }
    }

    @Test
    void theRollLanesProduceAllThreeCounts() {
        Map<Integer, Integer> histogram = new HashMap<>();
        for (int index = 0; index < 300; index++) {
            int size = band(index, "surface").size();
            histogram.merge(size, 1, Integer::sum);
        }
        assertEquals(Set.of(0, 1, 2), histogram.keySet(), "counts seen: " + histogram);
        // 300 draws at 20/50/30: every bucket is well clear of zero, and the middle
        // bucket should be the largest. Loose bounds, because this is a sample.
        assertTrue(histogram.get(0) > 20, "no-wagon count too low: " + histogram);
        assertTrue(histogram.get(1) > 70, "one-wagon count too low: " + histogram);
        assertTrue(histogram.get(2) > 30, "two-wagon count too low: " + histogram);
        assertTrue(histogram.get(1) > histogram.get(0));
        assertTrue(histogram.get(1) > histogram.get(2));
    }

    @Test
    void everyWagonSitsInsideAnEndWindowAndClearOfBothBoundaries() {
        int halfLength = RewardWagonLayout.HALF_LENGTH;
        for (int index = -12; index < 12; index++) {
            int lo = index * BAND;
            int hi = lo + BAND - 1;
            for (var wagon : band(index, "surface")) {
                int westEdge = wagon.centerX() - halfLength;
                int eastEdge = wagon.centerX() + halfLength;
                // 16 clear of the region's own columns ...
                assertTrue(westEdge >= lo + RewardWagonPlan.BOUNDARY_CLEARANCE,
                        "west edge " + westEdge + " in band " + index);
                assertTrue(eastEdge <= hi - RewardWagonPlan.BOUNDARY_CLEARANCE + 1,
                        "east edge " + eastEdge + " in band " + index);
                // ... and wholly inside one of the two 128 windows.
                boolean inWestWindow = westEdge >= lo && eastEdge < lo + RewardWagonPlan.END_WINDOW;
                boolean inEastWindow = westEdge >= hi + 1 - RewardWagonPlan.END_WINDOW && eastEdge <= hi;
                assertTrue(inWestWindow || inEastWindow,
                        "wagon " + wagon.centerX() + " is outside both windows of band " + index);
            }
        }
    }

    @Test
    void twoWagonsOfOneRegionKeepTheirDistance() {
        for (int index = -40; index < 40; index++) {
            for (String lane : new String[] {"surface", "underground", "starlight", "deeperdarker", "twilight"}) {
                List<RewardWagonPlan.Wagon> wagons = band(index, lane);
                for (int a = 0; a < wagons.size(); a++) {
                    for (int b = a + 1; b < wagons.size(); b++) {
                        int gap = Math.abs(wagons.get(a).centerX() - wagons.get(b).centerX());
                        assertTrue(gap >= RewardWagonPlan.MIN_SPACING,
                                lane + " band " + index + ": gap " + gap);
                    }
                }
            }
        }
    }

    @Test
    void aFullyReservedBandGetsNothing() {
        for (String lane : new String[] {"surface", "underground"}) {
            for (int index = 0; index < 10; index++) {
                assertTrue(RewardWagonPlan.forBand(SEED, index, BAND, lane, x -> true).isEmpty(),
                        lane + " band " + index);
            }
        }
    }

    @Test
    void aReservedColumnIsAvoided() {
        // Reserve the whole west window of band 1 and check nothing lands there.
        int lo = BAND;
        IntPredicate reserveWest = x -> x >= lo && x < lo + RewardWagonPlan.END_WINDOW;
        for (int attempt = 0; attempt < 20; attempt++) {
            var wagons = RewardWagonPlan.forBand(SEED + attempt, 1, BAND, "underground", reserveWest);
            for (var wagon : wagons) {
                int westEdge = wagon.centerX() - RewardWagonLayout.HALF_LENGTH;
                int eastEdge = wagon.centerX() + RewardWagonLayout.HALF_LENGTH;
                assertTrue(eastEdge < lo || westEdge >= lo + RewardWagonPlan.END_WINDOW,
                        "wagon at " + wagon.centerX() + " overlaps the reserved west window");
            }
        }
    }

    @Test
    void forcedSpawnEndPutsTheWagonOnTheNearSideOfSpawn() {
        // Regions east of spawn (index >= 0) keep their wagon at the west end; regions west
        // of spawn keep it at the east end. Both are the end nearest X = 0.
        for (int index = 0; index < 10; index++) {
            int lo = index * BAND;
            for (var wagon : band(index, "nether")) {
                assertTrue(wagon.centerX() < lo + RewardWagonPlan.END_WINDOW,
                        "band " + index + " nether wagon should sit at its west end, got " + wagon.centerX());
            }
        }
        for (int index = -10; index < 0; index++) {
            int hi = index * BAND + BAND - 1;
            for (var wagon : band(index, "nether")) {
                assertTrue(wagon.centerX() >= hi + 1 - RewardWagonPlan.END_WINDOW,
                        "band " + index + " nether wagon should sit at its east end, got " + wagon.centerX());
            }
        }
    }

    @Test
    void eachLaneRollsTheContentTheRequirementAsksFor() {
        assertEquals(RewardWagonContent.TWILIGHT_CHEST, RewardWagonContent.roll("twilight", 0.99F));
        assertEquals(RewardWagonContent.SOUND_TRAP, RewardWagonContent.roll("deeperdarker", 0.99F));
        assertEquals(RewardWagonContent.MOTOR_WAGON, RewardWagonContent.roll("aether", 0.99F));
        assertEquals(RewardWagonContent.DEEP_IRON, RewardWagonContent.roll("nether", 0.99F));
        assertEquals(RewardWagonContent.NONE, RewardWagonContent.roll("end", 0.0F));
        assertEquals(RewardWagonContent.NONE, RewardWagonContent.roll("voidscape", 0.0F));
    }

    @Test
    void theSurfaceRollSplitsThirtySeventy() {
        int iron = 0;
        int copper = 0;
        for (int i = 0; i < 2000; i++) {
            float roll = i / 2000.0F;
            if (RewardWagonContent.roll("surface", roll) == RewardWagonContent.SHALLOW_IRON) {
                iron++;
            } else {
                copper++;
            }
        }
        assertEquals(600, iron, "30% shallow iron");
        assertEquals(1400, copper, "70% shallow copper");
    }

    @Test
    void theUndergroundRollSplitsSixtyTwentyTwenty() {
        int iron = 0;
        int gold = 0;
        int copper = 0;
        for (int i = 0; i < 2000; i++) {
            RewardWagonContent content = RewardWagonContent.roll("underground", i / 2000.0F);
            switch (content) {
                case DEEP_IRON -> iron++;
                case DEEP_GOLD -> gold++;
                case DEEP_COPPER -> copper++;
                default -> throw new AssertionError("unexpected " + content);
            }
        }
        assertEquals(1200, iron);
        assertEquals(400, gold);
        assertEquals(400, copper);
    }

    @Test
    void theStarlightRollSplitsFortySixty() {
        int deepsilver = 0;
        int saltpeter = 0;
        for (int i = 0; i < 1000; i++) {
            if (RewardWagonContent.roll("starlight", i / 1000.0F) == RewardWagonContent.DEEPSILVER) {
                deepsilver++;
            } else {
                saltpeter++;
            }
        }
        assertEquals(400, deepsilver);
        assertEquals(600, saltpeter);
    }

    @Test
    void aBandTooNarrowForTheRulesGetsNothing() {
        assertTrue(RewardWagonPlan.forBand(SEED, 0, 100, "surface", NOTHING_RESERVED).isEmpty());
    }

    @Test
    void theOreContentsCarryBothBlocksAndTheSpecialsCarryNeither() {
        for (RewardWagonContent content : RewardWagonContent.values()) {
            if (content.isOre()) {
                assertNotNull(content.oreBlock(), content + " ore block");
                assertNotNull(content.rawBlock(), content + " raw block");
            } else {
                assertEquals(null, content.oreBlock(), content + " ore block");
                assertEquals(null, content.rawBlock(), content + " raw block");
            }
        }
    }

    @Test
    void saltpeterFallsBackToItsOnlyBlockForm() {
        // Eternal Starlight 0.9.0 has no raw saltpeter block; the player chose the solid
        // block as the stand-in, so this is asserted rather than left to drift.
        assertEquals("eternal_starlight:saltpeter_block", RewardWagonContent.SALTPETER.rawBlock());
        assertFalse(RewardWagonContent.SALTPETER.rawBlock().contains("raw"));
    }
}