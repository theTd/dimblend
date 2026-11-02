package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The five-step ore procedure the requirement spells out. */
class RewardWagonOreTest {
    private static final int SAMPLES = 400;

    @Test
    void everyPatternCoversEachMarkedCellExactlyOnce() {
        for (long seed = 0; seed < 50; seed++) {
            RewardWagonOre.Pattern pattern = RewardWagonOre.build(seed);
            Set<String> seen = new HashSet<>();
            for (var cell : pattern.cells()) {
                String key = cell.dx() + ":" + cell.dz();
                assertTrue(seen.add(key), "cell " + key + " appears twice in seed " + seed);
            }
            assertEquals(17, pattern.cells().size(), "seed " + seed);
        }
    }

    @Test
    void theGroupsKeepTheirMarker() {
        for (long seed = 0; seed < 50; seed++) {
            int one = 0;
            int two = 0;
            int three = 0;
            for (var cell : RewardWagonOre.build(seed).cells()) {
                switch (cell.marker()) {
                    case 1 -> one++;
                    case 2 -> two++;
                    case 3 -> three++;
                    default -> throw new AssertionError("marker " + cell.marker());
                }
            }
            assertEquals(10, one, "seed " + seed);
            assertEquals(6, two, "seed " + seed);
            assertEquals(1, three, "seed " + seed);
        }
    }

    @Test
    void betweenTwoAndSixOfGroupOneBecomeAir() {
        Set<Integer> counts = new HashSet<>();
        for (long seed = 0; seed < SAMPLES; seed++) {
            int air = 0;
            for (var cell : RewardWagonOre.build(seed).cells()) {
                if (cell.marker() == 1 && cell.fill() == RewardWagonOre.Fill.AIR) {
                    air++;
                }
            }
            assertTrue(air >= RewardWagonOre.MIN_AIR && air <= RewardWagonOre.MAX_AIR,
                    "seed " + seed + " carved " + air);
            counts.add(air);
        }
        assertEquals(Set.of(2, 3, 4, 5, 6), counts, "every allowed count should occur");
    }

    @Test
    void groupTwoAlwaysGivesExactlyTwoRawBlocks() {
        for (long seed = 0; seed < SAMPLES; seed++) {
            int raw = 0;
            for (var cell : RewardWagonOre.build(seed).cells()) {
                if (cell.marker() == 2 && cell.fill() == RewardWagonOre.Fill.RAW) {
                    raw++;
                }
            }
            assertEquals(2, raw, "seed " + seed);
        }
    }

    @Test
    void nobodyButTheRawCellsIsRaw() {
        for (long seed = 0; seed < SAMPLES; seed++) {
            for (var cell : RewardWagonOre.build(seed).cells()) {
                if (cell.fill() == RewardWagonOre.Fill.RAW) {
                    assertTrue(cell.marker() == 2 || cell.marker() == 3,
                            "marker " + cell.marker() + " went raw at seed " + seed);
                }
            }
        }
    }

    @Test
    void groupThreeIsRawAboutHalfTheTime() {
        int raw = 0;
        int samples = 2000;
        for (long seed = 0; seed < samples; seed++) {
            for (var cell : RewardWagonOre.build(seed).cells()) {
                if (cell.marker() == 3) {
                    if (cell.fill() == RewardWagonOre.Fill.RAW) {
                        raw++;
                    }
                }
            }
        }
        double frequency = raw / (double) samples;
        assertTrue(frequency > 0.45 && frequency < 0.55,
                "group 3 went raw " + frequency + " of the time");
    }

    @Test
    void theRawCountIsTwoOrThree() {
        Set<Integer> counts = new HashSet<>();
        for (long seed = 0; seed < SAMPLES; seed++) {
            counts.add(RewardWagonOre.build(seed).rawCells().size());
        }
        assertEquals(Set.of(2, 3), counts);
    }

    @Test
    void theSameSeedGivesTheSamePattern() {
        for (long seed : new long[] {0, 1, 42, -7, Long.MAX_VALUE}) {
            assertEquals(RewardWagonOre.build(seed), RewardWagonOre.build(seed), "seed " + seed);
        }
    }

    @Test
    void differentSeedsDiffer() {
        Set<String> shapes = new HashSet<>();
        for (long seed = 0; seed < 100; seed++) {
            StringBuilder shape = new StringBuilder();
            for (var cell : RewardWagonOre.build(seed).cells()) {
                shape.append(cell.dx()).append(',').append(cell.dz())
                        .append('=').append(cell.fill()).append(';');
            }
            shapes.add(shape.toString());
        }
        assertTrue(shapes.size() > 50, "100 seeds gave only " + shapes.size() + " distinct patterns");
    }

    @Test
    void visibleCellsAreEveryCellThatIsNotAir() {
        for (long seed = 0; seed < 20; seed++) {
            RewardWagonOre.Pattern pattern = RewardWagonOre.build(seed);
            List<RewardWagonOre.Cell> visible = RewardWagonOre.visible(pattern);
            int notAir = 0;
            for (var cell : pattern.cells()) {
                if (cell.fill() != RewardWagonOre.Fill.AIR) {
                    notAir++;
                }
            }
            assertEquals(notAir, visible.size(), "seed " + seed);
        }
    }

    @Test
    void airOnlyEverRemovesGroupOne() {
        for (long seed = 0; seed < SAMPLES; seed++) {
            for (var cell : RewardWagonOre.build(seed).cells()) {
                if (cell.fill() == RewardWagonOre.Fill.AIR) {
                    assertEquals(1, cell.marker(), "group " + cell.marker() + " carved at seed " + seed);
                }
            }
        }
    }
}