package dimblend.worldgen;

import java.util.HashMap;
import java.util.Map;

/**
 * Seeded sequential assignment of rotating-dimension regions to delegate indexes.
 *
 * <p>Fixed distances (0–20, 32, 33) are pinned to the north-star script. Random
 * distances draw from the remaining pools. Non-surface delegates stay different
 * from the previous region. A random surface region is never isolated: runs are
 * at least two long (longer runs allowed), may attach to the fixed surface at
 * |20|, and may cross a far-window boundary. Every eligible delegate appears at
 * least once per coverage window: 21–31, then repeating 16-wide windows from 34.
 * Surface keeps weight 2 on the roll that opens or extends a run; the cell that
 * closes a newly opened run repeats the opener's delegate without rolling.
 */
public final class BandLaneAssigner {
    public static final int SURFACE_WEIGHT = 2;

    public static final byte SURFACE = 0;
    public static final byte UNDERGROUND = 1;
    public static final byte NETHER = 2;
    public static final byte END = 3;
    public static final byte AETHER = 4;
    public static final byte TWILIGHT = 5;
    public static final byte STARLIGHT = 6;
    public static final byte OTHERSIDE = 7;
    public static final byte VOIDSCAPE = 8;
    public static final byte MOD = 9;

    static final int MID_RANDOM_START = 21;
    static final int MID_RANDOM_END = 31;
    static final int FAR_RANDOM_START = 34;
    static final int FAR_WINDOW = 16;

    private static final byte[] POOL_MID = {
            SURFACE, UNDERGROUND, NETHER, AETHER, TWILIGHT, STARLIGHT, OTHERSIDE
    };
    private static final byte[] POOL_FAR = {
            SURFACE, UNDERGROUND, NETHER, END, AETHER, TWILIGHT, STARLIGHT, OTHERSIDE, VOIDSCAPE, MOD
    };

    private final byte[] kinds;
    private final long seed;
    private final Map<Integer, Integer> cache = new HashMap<>();

    public BandLaneAssigner(byte[] kinds, long seed) {
        this.kinds = kinds.clone();
        this.seed = seed;
        requireKind(SURFACE);
        requireKind(UNDERGROUND);
        requireKind(NETHER);
        requireKind(END);
        this.cache.put(0, firstOf(SURFACE));
    }

    public long seed() {
        return this.seed;
    }

    public byte[] kinds() {
        return this.kinds.clone();
    }

    public int delegateIndex(int region) {
        synchronized (this.cache) {
            Integer cached = this.cache.get(region);
            if (cached != null) {
                return cached;
            }
            int step = region > 0 ? 1 : -1;
            int current = this.cache.get(0);
            for (int r = step; ; r += step) {
                Integer known = this.cache.get(r);
                if (known != null) {
                    current = known;
                    if (r == region) {
                        return current;
                    }
                    continue;
                }
                current = assign(r, current);
                this.cache.put(r, current);
                if (r == region) {
                    return current;
                }
            }
        }
    }

    private int assign(int region, int previous) {
        int distance = Math.abs(region);
        int fixed = fixedKind(distance);
        if (fixed >= 0) {
            int index = firstOf((byte) fixed);
            return index >= 0 ? index : firstOf(SURFACE);
        }
        // The closer of a run opened on the spawn side is not rolled: it repeats that
        // delegate. Derived from the cache so a later delegateIndex call can resume a
        // partially walked side without a sticky pending flag.
        if (mustClose(region)) {
            return this.cachedIndex(region - Integer.signum(region));
        }
        byte[] wanted = pool(distance);
        boolean[] seen = seenInWindow(region, distance, wanted);
        int otherMissing = missingNonSurface(wanted, seen);
        boolean surfaceSeen = surfaceSeen(seen);
        boolean previousSurface = this.kinds[previous] == SURFACE;
        boolean outwardRandom = fixedKind(distance + 1) < 0;
        int remaining = windowEnd(distance) - distance + 1;
        // Cost, not count: an unseen surface needs 2 slots unless it can attach to the
        // previous surface or close across the window edge. Windows stay wider than that
        // cost (mid 11 >= 8, far 16 >= 10, far+MOD 16 >= 11), so force never starts at a
        // window head and never asks the last in-window slot to begin a pair whose closer
        // would land on a fixed non-surface (region 31 / -31).
        int surfaceCost = surfaceCost(surfaceSeen, previousSurface, remaining, outwardRandom);
        boolean forceUnseen = remaining <= otherMissing + surfaceCost;
        boolean canAttach = previousSurface;
        boolean canOpen = !previousSurface && outwardRandom && openBudget(remaining, otherMissing);
        int picked = roll(region, previous, wanted, forceUnseen ? seen : null, canAttach, canOpen);
        if (picked < 0) {
            throw new IllegalStateException("no legal delegate for region " + region);
        }
        return picked;
    }

    /**
     * True when {@code region}'s spawn-side neighbor opened a surface run that is still
     * length 1. Fixed surface at |20| does not open a run: attaching there is optional,
     * and the attached cell already has a surface neighbor so the outward cell stays free.
     */
    private boolean mustClose(int region) {
        int step = Integer.signum(region);
        int spawnSide = region - step;
        if (fixedKind(Math.abs(spawnSide)) >= 0) {
            return false;
        }
        if (this.kinds[this.cachedIndex(spawnSide)] != SURFACE) {
            return false;
        }
        int anchor = spawnSide - step;
        return this.kinds[this.cachedIndex(anchor)] != SURFACE;
    }

    private int cachedIndex(int region) {
        Integer index = this.cache.get(region);
        if (index == null) {
            throw new IllegalStateException("region " + region + " assigned out of order");
        }
        return index;
    }

    private int roll(int region, int previous, byte[] wanted, boolean[] seen, boolean canAttach, boolean canOpen) {
        int total = 0;
        for (int i = 0; i < this.kinds.length; i++) {
            if (!eligible(i, previous, wanted, seen, canAttach, canOpen)) {
                continue;
            }
            total += weight(this.kinds[i]);
        }
        if (total <= 0) {
            return -1;
        }
        int roll = mix(this.seed, region);
        if (roll < 0) {
            roll = ~roll;
        }
        roll %= total;
        for (int i = 0; i < this.kinds.length; i++) {
            if (!eligible(i, previous, wanted, seen, canAttach, canOpen)) {
                continue;
            }
            int w = weight(this.kinds[i]);
            if (roll < w) {
                return i;
            }
            roll -= w;
        }
        return -1;
    }

    private boolean eligible(int index, int previous, byte[] wanted, boolean[] seen, boolean canAttach, boolean canOpen) {
        if (!contains(wanted, this.kinds[index])) {
            return false;
        }
        if (seen != null && seen[index]) {
            return false;
        }
        if (this.kinds[index] == SURFACE) {
            // Attach/extend keeps the run on one delegate. A new run may roll any surface
            // delegate; the closer then repeats whichever index this roll picked.
            if (canAttach) {
                return index == previous;
            }
            return canOpen && index != previous;
        }
        return index != previous;
    }

    private static int surfaceCost(boolean surfaceSeen, boolean previousSurface, int remaining, boolean outwardRandom) {
        if (surfaceSeen) {
            return 0;
        }
        if (previousSurface || remaining >= 2) {
            return previousSurface ? 1 : 2;
        }
        if (remaining == 1 && outwardRandom) {
            return 1;
        }
        return remaining + 1;
    }

    /** Slots a new surface run may spend without crowding out unseen non-surface delegates. */
    private static boolean openBudget(int remaining, int otherMissing) {
        int spent = remaining >= 2 ? 2 : 1;
        return remaining >= spent && remaining - spent >= otherMissing;
    }

    private boolean[] seenInWindow(int region, int distance, byte[] wanted) {
        boolean[] seen = new boolean[this.kinds.length];
        int sign = Integer.signum(region);
        int start = windowStart(distance);
        for (int d = start; d < distance; d++) {
            Integer index = this.cache.get(sign * d);
            if (index != null && contains(wanted, this.kinds[index])) {
                seen[index] = true;
            }
        }
        return seen;
    }

    private int missingNonSurface(byte[] wanted, boolean[] seen) {
        int missing = 0;
        for (int i = 0; i < this.kinds.length; i++) {
            if (this.kinds[i] == SURFACE || !contains(wanted, this.kinds[i]) || seen[i]) {
                continue;
            }
            missing++;
        }
        return missing;
    }

    private boolean surfaceSeen(boolean[] seen) {
        for (int i = 0; i < this.kinds.length; i++) {
            if (seen[i] && this.kinds[i] == SURFACE) {
                return true;
            }
        }
        return false;
    }

    static byte[] pool(int distance) {
        if (distance >= MID_RANDOM_START && distance <= MID_RANDOM_END) {
            return POOL_MID;
        }
        return POOL_FAR;
    }

    static int windowStart(int distance) {
        if (distance >= MID_RANDOM_START && distance <= MID_RANDOM_END) {
            return MID_RANDOM_START;
        }
        return FAR_RANDOM_START + ((distance - FAR_RANDOM_START) / FAR_WINDOW) * FAR_WINDOW;
    }

    static int windowEnd(int distance) {
        if (distance >= MID_RANDOM_START && distance <= MID_RANDOM_END) {
            return MID_RANDOM_END;
        }
        return windowStart(distance) + FAR_WINDOW - 1;
    }

    static int fixedKind(int distance) {
        return switch (distance) {
            case 0, 1, 3, 4, 8, 9, 14, 15, 20 -> SURFACE;
            case 2, 5, 6, 10, 13 -> UNDERGROUND;
            case 7, 11, 12 -> NETHER;
            case 16 -> AETHER;
            case 17 -> TWILIGHT;
            case 18 -> STARLIGHT;
            case 19 -> OTHERSIDE;
            case 32 -> END;
            case 33 -> VOIDSCAPE;
            default -> -1;
        };
    }

    private void requireKind(byte kind) {
        if (firstOf(kind) < 0) {
            throw new IllegalStateException("rotating delegates missing kind " + kind);
        }
    }

    private int firstOf(byte kind) {
        for (int i = 0; i < this.kinds.length; i++) {
            if (this.kinds[i] == kind) {
                return i;
            }
        }
        return -1;
    }

    private static boolean contains(byte[] wanted, byte kind) {
        for (byte value : wanted) {
            if (value == kind) {
                return true;
            }
        }
        return false;
    }

    private static int weight(byte kind) {
        return kind == SURFACE ? SURFACE_WEIGHT : 1;
    }

    private static int mix(long seed, int region) {
        long x = seed;
        x ^= (long) region * 0x9E3779B97F4A7C15L;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (int) x;
    }
}
