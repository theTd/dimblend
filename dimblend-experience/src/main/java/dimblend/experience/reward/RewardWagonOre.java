package dimblend.experience.reward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The ore pattern one wagon carries: which content cells hold what.
 *
 * <p>The requirement's procedure, step by step:
 * <ol>
 *   <li>fill every marked cell (groups 1, 2 and 3) with the rolled ore block;</li>
 *   <li>turn 2 to 6 of group 1 into air;</li>
 *   <li>turn exactly 2 of group 2 into the raw-ore block;</li>
 *   <li>with 50% probability turn the single group-3 cell into the raw-ore block;</li>
 *   <li>on top of each raw-ore block (one further block up) put the ore block itself.</li>
 * </ol>
 *
 * <p>Groups 1 and 2 are the ten and six cells the drawing marks; group 3 is the one bogey
 * cell, which the requirement puts the chest and the sound trap on as well — one cell,
 * three kinds of content, and the player confirmed that reading.
 *
 * <p>This class is pure: it decides positions and returns them, and never touches a level.
 */
public final class RewardWagonOre {
    /** What one marked cell ends up holding. */
    public enum Fill {
        /** Left empty. */
        AIR,
        /** The rolled ore block. */
        ORE,
        /** The rolled raw-ore block, which also gets an ore block on top of it. */
        RAW
    }

    /** One marked cell's outcome, at its deck offsets. */
    public record Cell(int dx, int dz, int marker, Fill fill) {
    }

    /** A whole pattern: one entry per marked cell. */
    public record Pattern(List<Cell> cells) {
        /** Cells that get an ore block above them: every raw cell. */
        public List<Cell> rawCells() {
            List<Cell> out = new ArrayList<>();
            for (Cell cell : cells) {
                if (cell.fill() == Fill.RAW) {
                    out.add(cell);
                }
            }
            return out;
        }
    }

    /** Fewest group-1 cells that may be carved out. */
    public static final int MIN_AIR = 2;
    /** Most group-1 cells that may be carved out. */
    public static final int MAX_AIR = 6;
    /** Group-2 cells that always become raw ore. */
    public static final int GROUP_TWO_RAW = 2;
    /** Probability that the single group-3 cell becomes raw ore. */
    public static final float GROUP_THREE_CHANCE = 0.5F;

    private RewardWagonOre() {
    }

    /**
     * Builds one pattern.
     *
     * @param seed the wagon's own seed, so the pattern survives a reload
     */
    public static Pattern build(long seed) {
        Random random = new Random(seed);
        List<RewardWagonLayout.Cell> groupOne = shuffled(RewardWagonLayout.marked(1), random);
        List<RewardWagonLayout.Cell> groupTwo = shuffled(RewardWagonLayout.marked(2), random);
        List<RewardWagonLayout.Cell> groupThree = RewardWagonLayout.marked(3);

        int airCount = MIN_AIR + random.nextInt(MAX_AIR - MIN_AIR + 1);
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < groupOne.size(); i++) {
            cells.add(new Cell(groupOne.get(i).dx(), groupOne.get(i).dz(), 1,
                    i < airCount ? Fill.AIR : Fill.ORE));
        }
        for (int i = 0; i < groupTwo.size(); i++) {
            cells.add(new Cell(groupTwo.get(i).dx(), groupTwo.get(i).dz(), 2,
                    i < GROUP_TWO_RAW ? Fill.RAW : Fill.ORE));
        }
        boolean thirdRaw = random.nextFloat() < GROUP_THREE_CHANCE;
        for (RewardWagonLayout.Cell cell : groupThree) {
            cells.add(new Cell(cell.dx(), cell.dz(), 3, thirdRaw ? Fill.RAW : Fill.ORE));
        }
        return new Pattern(List.copyOf(cells));
    }

    /** Every marked cell of a pattern that ends up holding something. */
    public static List<Cell> visible(Pattern pattern) {
        List<Cell> out = new ArrayList<>();
        for (Cell cell : pattern.cells()) {
            if (cell.fill() != Fill.AIR) {
                out.add(cell);
            }
        }
        return out;
    }

    private static List<RewardWagonLayout.Cell> shuffled(List<RewardWagonLayout.Cell> cells, Random random) {
        List<RewardWagonLayout.Cell> copy = new ArrayList<>(cells);
        // A fixed seed and a fixed algorithm: Collections.shuffle with a seeded Random is
        // reproducible, which is all this needs.
        Collections.shuffle(copy, random);
        return copy;
    }
}