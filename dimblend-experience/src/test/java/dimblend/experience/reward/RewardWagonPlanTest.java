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
    void everyWagonSitsInsideTheInteriorWindow() {
        // 2026-11-04: 「每个区域开始 128格后 到1920格 之内的部分」 — whole wagon, not just centre.
        int halfLength = RewardWagonLayout.HALF_LENGTH;
        assertEquals(128, RewardWagonPlan.WINDOW_START);
        assertEquals(1920, RewardWagonPlan.WINDOW_END);
        for (int index = -12; index < 12; index++) {
            int lo = index * BAND;
            for (var wagon : band(index, "surface")) {
                int westEdge = wagon.centerX() - halfLength;
                int eastEdge = wagon.centerX() + halfLength;
                assertTrue(westEdge >= lo + RewardWagonPlan.WINDOW_START,
                        "west edge " + westEdge + " is before the window in band " + index);
                assertTrue(eastEdge <= lo + RewardWagonPlan.WINDOW_END,
                        "east edge " + eastEdge + " is past the window in band " + index);
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
        // Reserve the first 372 blocks of band 1's window and check nothing covers them.
        int lo = BAND;
        int reservedTo = lo + 500;
        IntPredicate reserveStart = x -> x >= lo + RewardWagonPlan.WINDOW_START && x < reservedTo;
        for (int attempt = 0; attempt < 20; attempt++) {
            var wagons = RewardWagonPlan.forBand(SEED + attempt, 1, BAND, "underground", reserveStart);
            for (var wagon : wagons) {
                int westEdge = wagon.centerX() - RewardWagonLayout.HALF_LENGTH;
                assertTrue(westEdge >= reservedTo,
                        "wagon at " + wagon.centerX() + " overlaps the reserved strip");
            }
        }
    }

    @Test
    void theAetherWagonSitsAtTheSpawnSideEdgeOfTheWindow() {
        // "位于最开始的位置": the spawn-side edge of the window, which is its low edge for regions
        // east of spawn and its high edge for regions west of it.
        for (int index = 0; index < 10; index++) {
            int edge = index * BAND + RewardWagonPlan.WINDOW_START + RewardWagonLayout.HALF_LENGTH;
            var wagons = band(index, "aether");
            assertEquals(1, wagons.size(), "band " + index);
            assertEquals(edge, wagons.get(0).centerX(), "band " + index);
        }
        for (int index = -10; index < 0; index++) {
            int edge = index * BAND + RewardWagonPlan.WINDOW_END - RewardWagonLayout.HALF_LENGTH;
            var wagons = band(index, "aether");
            assertEquals(1, wagons.size(), "band " + index);
            assertEquals(edge, wagons.get(0).centerX(), "band " + index);
        }
    }

    @Test
    void aReservedStripAtTheSpawnEdgePushesTheAetherWagonInwards() {
        int lo = 0;
        int reservedTo = lo + RewardWagonPlan.WINDOW_START + 40;
        IntPredicate reserveStart = x -> x < reservedTo;
        var wagons = RewardWagonPlan.forBand(SEED, 0, BAND, "aether", reserveStart);
        assertEquals(1, wagons.size());
        int centre = wagons.get(0).centerX();
        assertTrue(centre - RewardWagonLayout.HALF_LENGTH >= reservedTo,
                "wagon at " + centre + " still overlaps the reserved strip");
    }

    @Test
    void theNetherWagonMayLandAnywhereInTheWindow() {
        // The nether line only says "exactly one"; with one window that is a draw anywhere in it,
        // not a pin to the spawn edge (that pin belongs to the aether line alone).
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int index = 0; index < 80; index++) {
            for (var wagon : band(index, "nether")) {
                int offset = wagon.centerX() - index * BAND;
                lowest = Math.min(lowest, offset);
                highest = Math.max(highest, offset);
            }
        }
        assertTrue(lowest < 400, "nether wagons never land near the start of the window: " + lowest);
        assertTrue(highest > 1500, "nether wagons never land near the end of the window: " + highest);
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
    void theSurfaceRollSplitsThirtyFiftyTwenty() {
        int copper = 0;
        int zinc = 0;
        int iron = 0;
        for (int i = 0; i < 2000; i++) {
            switch (RewardWagonContent.roll("surface", i / 2000.0F)) {
                case SHALLOW_COPPER -> copper++;
                case SHALLOW_ZINC -> zinc++;
                case SHALLOW_IRON -> iron++;
                default -> throw new AssertionError("unexpected " + RewardWagonContent.roll("surface", i / 2000.0F));
            }
        }
        assertEquals(600, copper, "30% 浅层铜");
        assertEquals(1000, zinc, "50% 浅层锌");
        assertEquals(400, iron, "20% 浅层铁");
    }

    @Test
    void theUndergroundRollSplitsFiftyThirtyTwenty() {
        int iron = 0;
        int zinc = 0;
        int copper = 0;
        for (int i = 0; i < 2000; i++) {
            RewardWagonContent content = RewardWagonContent.roll("underground", i / 2000.0F);
            switch (content) {
                case DEEP_IRON -> iron++;
                case DEEP_ZINC -> zinc++;
                case DEEP_COPPER -> copper++;
                default -> throw new AssertionError("unexpected " + content);
            }
        }
        assertEquals(1000, iron, "50% 深层铁");
        assertEquals(600, zinc, "30% 深层锌");
        assertEquals(400, copper, "20% 深层铜");
    }

    @Test
    void theZincOresComeFromCreate() {
        // Vanilla has no zinc: the 2026-11-04 table is only buildable through Create's blocks.
        assertEquals("create:zinc_ore", RewardWagonContent.SHALLOW_ZINC.oreBlock());
        assertEquals("create:raw_zinc_block", RewardWagonContent.SHALLOW_ZINC.rawBlock());
        assertEquals("create:deepslate_zinc_ore", RewardWagonContent.DEEP_ZINC.oreBlock());
        assertEquals("create:raw_zinc_block", RewardWagonContent.DEEP_ZINC.rawBlock());
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
