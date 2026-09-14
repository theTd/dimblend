package dimblend.worldgen;

import java.util.HashMap;
import java.util.Map;

/**
 * Seeded sequential assignment of rotating-dimension regions to delegate indexes.
 *
 * <p>Fixed distances (0–21, 32) are pinned to the north-star script. Random
 * distances draw from the remaining pools, keep adjacent regions on different
 * delegates, and guarantee every eligible delegate appears at least once in
 * each coverage window: 22–31, then repeating 16-wide windows from 33.
 */
public final class BandLaneAssigner {
    public static final int SURFACE_WEIGHT = 3;

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

    static final int MID_RANDOM_START = 22;
    static final int MID_RANDOM_END = 31;
    static final int FAR_RANDOM_START = 33;
    static final int FAR_WINDOW = 16;

    private static final byte[] POOL_MID = {
            SURFACE, UNDERGROUND, NETHER, AETHER, TWILIGHT, STARLIGHT, OTHERSIDE, VOIDSCAPE, MOD
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
        byte[] wanted = pool(distance);
        boolean[] seen = seenInWindow(region, distance, wanted);
        int missing = missingCount(wanted, seen);
        int remaining = windowEnd(distance) - distance + 1;
        boolean forceUnseen = remaining <= missing;
        int picked = roll(region, previous, wanted, forceUnseen ? seen : null);
        if (picked >= 0) {
            return picked;
        }
        if (forceUnseen) {
            picked = roll(region, previous, wanted, null);
            if (picked >= 0) {
                return picked;
            }
        }
        return previous;
    }

    private int roll(int region, int previous, byte[] wanted, boolean[] seen) {
        int total = 0;
        for (int i = 0; i < this.kinds.length; i++) {
            if (!eligible(i, previous, wanted, seen)) {
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
            if (!eligible(i, previous, wanted, seen)) {
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

    private boolean eligible(int index, int previous, byte[] wanted, boolean[] seen) {
        if (index == previous) {
            return false;
        }
        if (!contains(wanted, this.kinds[index])) {
            return false;
        }
        return seen == null || !seen[index];
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

    private int missingCount(byte[] wanted, boolean[] seen) {
        int missing = 0;
        for (int i = 0; i < this.kinds.length; i++) {
            if (contains(wanted, this.kinds[i]) && !seen[i]) {
                missing++;
            }
        }
        return missing;
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
            case 0, 1, 3, 4, 8, 9, 14, 15, 21 -> SURFACE;
            case 2, 5, 6, 10, 13 -> UNDERGROUND;
            case 7, 11, 12 -> NETHER;
            case 16 -> AETHER;
            case 17 -> TWILIGHT;
            case 18 -> STARLIGHT;
            case 19 -> OTHERSIDE;
            case 20 -> VOIDSCAPE;
            case 32 -> END;
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
