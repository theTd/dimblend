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
 * <p><b>The window.</b> The 2026-11-02 wording put wagons "within 128 blocks of either end of the
 * region". The 2026-11-04 correction replaces it: "车厢位置位于每个区域开始 128格后 到1920格
 * 之内的部分". So there is one window per region now — from 128 to 1920 blocks after the region
 * start — and the whole wagon has to sit inside it, not only its centre. At the default 2048-wide
 * region that leaves the first 128 and the last 128 blocks empty, which is also what keeps a wagon
 * clear of the region boundary columns at both ends.
 *
 * <p><b>Where the "spawn end" went.</b> With one window there is no pair of ends left to choose
 * from, so a lane that asks for its wagon "at the start" (the aether line) takes the spawn-side
 * edge of the window and walks inwards only as far as a reserved column forces it to.
 */
public final class RewardWagonPlan {
    /** One wagon: where its centre sits, what it carries, and the seed its ore pattern uses. */
    public record Wagon(int centerX, RewardWagonContent content, long seed) {
    }

    /** Blocks after the region start where the wagon window opens. 2026-11-04 requirement. */
    public static final int WINDOW_START = 128;
    /** Blocks after the region start where the wagon window closes. 2026-11-04 requirement. */
    public static final int WINDOW_END = 1920;
    /** Minimum centre-to-centre distance between two wagons of one region. */
    public static final int MIN_SPACING = 16;
    private static final int MAX_REROLLS = 16;
    /** How far the spawn-side edge walks inwards before giving up on a fully reserved strip. */
    private static final int EDGE_SCAN = 256;

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
        if (bandSize <= WINDOW_START + 2 * RewardWagonLayout.HALF_LENGTH) {
            return List.of();
        }
        Random random = new Random(mix(worldSeed, bandIndex));
        int count = count(lane, random);
        if (count <= 0) {
            return List.of();
        }
        int lo = bandIndex * bandSize;
        // The whole wagon has to be inside [lo + 128, lo + 1920]; a region narrower than the
        // requirement asks for keeps its own end clearance instead of reading past its edge.
        int windowLo = lo + WINDOW_START;
        int windowHi = Math.min(lo + WINDOW_END, lo + bandSize - 1);
        int min = windowLo + RewardWagonLayout.HALF_LENGTH;
        int max = windowHi - RewardWagonLayout.HALF_LENGTH;
        if (min > max) {
            return List.of();
        }
        RewardWagonContent.CountRule rule = RewardWagonContent.countRule(lane);
        boolean atSpawnEdge = rule == RewardWagonContent.CountRule.ONE_AT_SPAWN_END;
        boolean westIsSpawnEnd = bandIndex >= 0;

        List<Wagon> wagons = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int centerX = atSpawnEdge
                    ? spawnEdgeCentre(westIsSpawnEnd, min, max, reservedX, wagons)
                    : drawCenter(random, min, max, reservedX, wagons);
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

    /**
     * The free centre nearest the spawn end of the window.
     *
     * <p>Regions east of spawn have their spawn end at the window's low edge, regions west of spawn
     * at its high edge. The walk moves inwards one block at a time and stops at the first centre
     * that is free: a reserved strip (the starter depot reaches 260 blocks past spawn, and the
     * window of the region just west of spawn starts inside it) only pushes the wagon as far as it
     * has to go.
     */
    private static int spawnEdgeCentre(boolean westIsSpawnEnd, int min, int max, IntPredicate reservedX,
            List<Wagon> taken) {
        int step = westIsSpawnEnd ? 1 : -1;
        int start = westIsSpawnEnd ? min : max;
        for (int index = 0; index < EDGE_SCAN; index++) {
            int candidate = start + step * index;
            if (candidate < min || candidate > max) {
                return NO_POSITION;
            }
            if (!blocked(candidate, reservedX, taken)) {
                return candidate;
            }
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
            case ONE_AT_SPAWN_END, ONE_RANDOM -> 1;
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
