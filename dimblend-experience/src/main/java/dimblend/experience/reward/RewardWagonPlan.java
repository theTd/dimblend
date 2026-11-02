package dimblend.experience.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * Which reward wagons a region gets, and where.
 *
 * <p><b>Deterministic.</b> Everything here is derived from the world seed and the
 * region index alone, through {@link Random} (whose sequence the Java specification
 * fixes), so a restart, a reload or a second player sees the same wagons in the same
 * places. Nothing is stored to make that true.
 *
 * <p><b>The windows.</b> A region runs {@code [lo, hi]} with {@code hi = lo + bandSize - 1}.
 * The requirement puts wagons "within 128 blocks of either end of the region". The
 * player additionally asked for the whole wagon to stay at least 16 blocks away from any
 * boundary column, so the usable centre range is
 * {@code [lo + 16 + 4, lo + 128 - 4]} at the low end and {@code [hi + 1 - 128 + 4, hi + 1 - 16 - 4]}
 * at the high end, where 4 is the wagon's half length.
 *
 * <p><b>Two wagons are drawn independently.</b> The player chose that over "one at each
 * end", so both may land at the same end; a minimum spacing keeps them apart, and if the
 * roll cannot satisfy it the second wagon is dropped rather than stacked on the first.
 */
public final class RewardWagonPlan {
    /** One wagon: where its centre sits, what it carries, and the seed its ore pattern uses. */
    public record Wagon(int centerX, RewardWagonContent content, long seed) {
    }

    /** The requirement's "within 128 blocks of either end". */
    public static final int END_WINDOW = 128;
    /** How far the whole wagon stays from a boundary column. */
    public static final int BOUNDARY_CLEARANCE = 16;
    /** Minimum centre-to-centre distance between two wagons of one region. */
    public static final int MIN_SPACING = 16;
    private static final int MAX_REROLLS = 16;

    private RewardWagonPlan() {
    }

    /**
     * The wagons of one region.
     *
     * @param worldSeed the level's seed
     * @param bandIndex the region index, negative west of spawn
     * @param bandSize  the region width in blocks
     * @param lane      the lane name {@code dimblend} reports for this region
     * @param reservedX true for block X a wagon may not stand on (station halls, the
     *                  starter depot, the free power chassis)
     */
    public static List<Wagon> forBand(long worldSeed, int bandIndex, int bandSize, String lane,
            IntPredicate reservedX) {
        if (bandSize < END_WINDOW + 2 * (BOUNDARY_CLEARANCE + RewardWagonLayout.HALF_LENGTH)) {
            return List.of();
        }
        Random random = new Random(mix(worldSeed, bandIndex));
        int count = count(lane, random);
        if (count <= 0) {
            return List.of();
        }
        int lo = bandIndex * bandSize;
        int hi = lo + bandSize - 1;
        // Low end: the whole wagon inside the 128 window and 16 clear of the boundary at lo.
        int westMin = lo + BOUNDARY_CLEARANCE + RewardWagonLayout.HALF_LENGTH;
        int westMax = lo + END_WINDOW - RewardWagonLayout.HALF_LENGTH;
        // High end: the same against the boundary at hi + 1.
        int eastMin = hi + 1 - END_WINDOW + RewardWagonLayout.HALF_LENGTH;
        int eastMax = hi + 1 - BOUNDARY_CLEARANCE - RewardWagonLayout.HALF_LENGTH;
        boolean forcedSpawnEnd = RewardWagonContent.countRule(lane) == RewardWagonContent.CountRule.ONE_AT_SPAWN_END;
        boolean westIsSpawnEnd = bandIndex >= 0;

        List<Wagon> wagons = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            boolean west = forcedSpawnEnd ? westIsSpawnEnd : random.nextBoolean();
            int min = west ? westMin : eastMin;
            int max = west ? westMax : eastMax;
            int centerX = drawCenter(random, min, max, reservedX, wagons);
            if (centerX == NO_POSITION) {
                continue;
            }
            wagons.add(new Wagon(centerX, RewardWagonContent.roll(lane, random.nextFloat()),
                    random.nextLong()));
        }
        return List.copyOf(wagons);
    }

    private static final int NO_POSITION = Integer.MIN_VALUE;

    private static int drawCenter(Random random, int min, int max, IntPredicate reservedX, List<Wagon> taken) {
        for (int attempt = 0; attempt < MAX_REROLLS; attempt++) {
            int candidate = min + random.nextInt(max - min + 1);
            if (blocked(candidate, reservedX, taken)) {
                continue;
            }
            return candidate;
        }
        return NO_POSITION;
    }

    private static boolean blocked(int centerX, IntPredicate reservedX, List<Wagon> taken) {
        for (Wagon other : taken) {
            if (Math.abs(other.centerX() - centerX) < MIN_SPACING) {
                return true;
            }
        }
        for (int x = centerX - RewardWagonLayout.HALF_LENGTH;
                x <= centerX + RewardWagonLayout.HALF_LENGTH; x++) {
            if (reservedX.test(x)) {
                return true;
            }
        }
        return false;
    }

    private static int count(String lane, Random random) {
        return switch (RewardWagonContent.countRule(lane)) {
            case NONE -> 0;
            case ONE_AT_SPAWN_END -> 1;
            case ROLL_0_1_2 -> {
                float roll = random.nextFloat();
                if (roll < 0.20F) {
                    yield 0;
                }
                yield roll < 0.70F ? 1 : 2;
            }
        };
    }

    /** SplitMix64 finaliser: keeps adjacent region indices from producing related streams. */
    private static long mix(long worldSeed, int bandIndex) {
        long state = worldSeed + 0x9E3779B97F4A7C15L * (bandIndex + 1L);
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
