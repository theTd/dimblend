package dimblend.experience.reward;

import java.util.ArrayList;
import java.util.List;

/**
 * One pass over a wagon's plot box: what stands there, and which of those blocks the dissolve
 * has to reach.
 *
 * <p><b>Plot coordinates, never world coordinates.</b> A Sable sub-level's blocks are stored in
 * a plot parked millions of blocks away. {@code car.boundingBox()} is where the wagon
 * <i>appears</i>; {@code car.getPlot().getBoundingBox()} is where its blocks <i>are</i>. This
 * class walks the second, which needs no pose transform and no ownership test: the plot box
 * already holds this sub-level's blocks and nothing else.
 *
 * <p>The reading itself is handed in as a {@link Lookup}, so the rules below are testable with
 * no game running.
 */
public final class RewardWagonPlotScan {
    /**
     * The widest box this will walk on any axis.
     *
     * <p>The wagon is 9 x 3 x 4 blocks. A box wider than this is not a wagon — it is a plot
     * that has not been bounded yet, or another sub-level entirely — and deciding "the wagon is
     * empty" from it would delete something that was never read.
     */
    public static final int MAX_SPAN = 64;

    /** What one position holds, as far as the dissolve cares. */
    public enum Fill {
        AIR,
        /** A deck brick: the clean copycat slab. */
        SLAB,
        /** A deck brick that is also broken when the wagon dissolves. */
        COUPLER,
        /** A deck brick that is also the spot the dissolve's effects are pinned to. */
        BOGEY,
        /** A container with nothing in it; counts as taken. */
        EMPTY_CONTAINER,
        /** A container that still holds something; keeps the wagon alive. */
        FILLED_CONTAINER,
        /** Ore, a chest's contents, the sensor, TNT, the motor: anything the player came for. */
        CONTENT
    }

    /** A block position in the plot. Deliberately not Minecraft's {@code BlockPos}. */
    public record Pos(int x, int y, int z) {
    }

    /** Reads one position of the plot. */
    @FunctionalInterface
    public interface Lookup {
        Fill at(int x, int y, int z);
    }

    /**
     * Everything one pass found.
     *
     * @param occupants every non-air block, in the order the scan met it
     * @param couplers  the couplers, which the dissolve breaks before the wait
     * @param bogey     the first bogey met, or {@code null} when the wagon has none left
     */
    public record Result(List<RewardWagonDissolve.Occupant> occupants, List<Pos> couplers, Pos bogey) {
    }

    /**
     * Walks an inclusive box and collects what is in it.
     *
     * @return the result, or {@code null} when the box cannot be trusted: inverted (nothing has
     *         been written yet, so the plot has no bounds) or wider than {@link #MAX_SPAN}
     * @throws IllegalArgumentException when {@code lookup} is null
     */
    public static Result scan(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Lookup lookup) {
        if (lookup == null) {
            throw new IllegalArgumentException("A scan needs a lookup");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        // long arithmetic: an unbounded plot reports Integer.MAX_VALUE / MIN_VALUE, and the span
        // of that pair overflows int to a negative number, which would read as "small".
        if ((long) maxX - minX >= MAX_SPAN || (long) maxY - minY >= MAX_SPAN || (long) maxZ - minZ >= MAX_SPAN) {
            return null;
        }
        List<RewardWagonDissolve.Occupant> occupants = new ArrayList<>();
        List<Pos> couplers = new ArrayList<>();
        Pos bogey = null;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Fill fill = lookup.at(x, y, z);
                    if (fill == null || fill == Fill.AIR) {
                        continue;
                    }
                    occupants.add(occupantOf(fill));
                    if (fill == Fill.COUPLER) {
                        couplers.add(new Pos(x, y, z));
                    } else if (fill == Fill.BOGEY && bogey == null) {
                        bogey = new Pos(x, y, z);
                    }
                }
            }
        }
        return new Result(List.copyOf(occupants), List.copyOf(couplers), bogey);
    }

    /** How one filled position counts for {@link RewardWagonDissolve#isEmpty}. */
    public static RewardWagonDissolve.Occupant occupantOf(Fill fill) {
        return switch (fill) {
            case AIR -> throw new IllegalArgumentException("Air is not an occupant");
            case SLAB, COUPLER, BOGEY -> RewardWagonDissolve.Occupant.STRUCTURE;
            case EMPTY_CONTAINER -> RewardWagonDissolve.Occupant.EMPTY_CONTAINER;
            case FILLED_CONTAINER -> RewardWagonDissolve.Occupant.FILLED_CONTAINER;
            case CONTENT -> RewardWagonDissolve.Occupant.CONTENT;
        };
    }

    private RewardWagonPlotScan() {
    }
}
