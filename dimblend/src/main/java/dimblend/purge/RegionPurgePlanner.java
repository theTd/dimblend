package dimblend.purge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.LongPredicate;
import java.util.function.LongToIntFunction;

/**
 * Pure bookkeeping for region purge: region keys, file names, the X keep window and the idle
 * timer. JDK types only so it stays unit-testable without the game on the classpath.
 *
 * <p>Region keys use the {@code ChunkPos.asLong(regionX, regionZ)} packing, the same key vanilla
 * {@code RegionFileStorage} uses for its open-file cache. The keep window is X-only: the rotating
 * world is a strip along X, so a region is kept while any keep center is within the configured
 * chunk distance along X, regardless of Z.
 */
public final class RegionPurgePlanner {
    public static final int CHUNKS_PER_REGION_SHIFT = 5;
    public static final int CHUNKS_PER_REGION = 1 << CHUNKS_PER_REGION_SHIFT;
    /** Margin above pregen and view distance so purge never races chunks about to load. */
    public static final int KEEP_MARGIN_CHUNKS = 8;

    /** Region key to the game tick it was first seen eligible in an unbroken run of scans. */
    private final Map<Long, Long> eligibleSince = new HashMap<>();

    public static long key(int regionX, int regionZ) {
        return (long) regionX & 0xFFFFFFFFL | ((long) regionZ & 0xFFFFFFFFL) << 32;
    }

    public static int keyX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int keyZ(long key) {
        return (int) (key >>> 32 & 0xFFFFFFFFL);
    }

    public static int regionOfChunk(int chunk) {
        return chunk >> CHUNKS_PER_REGION_SHIFT;
    }

    /** Region key of a packed {@code ChunkPos.asLong} chunk key. */
    public static long regionKeyOfChunk(long chunkKey) {
        return key(regionOfChunk(keyX(chunkKey)), regionOfChunk(keyZ(chunkKey)));
    }

    public static int effectiveKeepChunks(int keepChunks, int xBehind, int xAhead, int viewDistance) {
        int pregen = Math.max(xBehind, xAhead) + KEEP_MARGIN_CHUNKS;
        int view = viewDistance + KEEP_MARGIN_CHUNKS;
        return Math.max(keepChunks, Math.max(pregen, view));
    }

    /** Chunk distance along X from {@code chunkX} to the nearest chunk column of the region; 0 inside. */
    public static int chunkDistanceX(int regionX, int chunkX) {
        int min = regionX << CHUNKS_PER_REGION_SHIFT;
        int max = min + CHUNKS_PER_REGION - 1;
        if (chunkX < min) {
            return min - chunkX;
        }
        return chunkX > max ? chunkX - max : 0;
    }

    /** Distance to the nearest keep center, or {@link Integer#MAX_VALUE} with no centers. */
    public static int nearestCenterDistance(int regionX, int[] centerChunkXs) {
        int best = Integer.MAX_VALUE;
        for (int center : centerChunkXs) {
            best = Math.min(best, chunkDistanceX(regionX, center));
        }
        return best;
    }

    /** Region key of a vanilla {@code r.X.Z.mca} file name; empty for anything else. */
    public static OptionalLong parseRegionFileName(String name) {
        return parseCoordinatePair(name, "r.", ".mca");
    }

    /** True for an oversized-chunk file {@code c.<chunkX>.<chunkZ>.mcc} whose chunk lies in the region. */
    public static boolean isOversizedChunkOf(String name, int regionX, int regionZ) {
        OptionalLong chunk = parseCoordinatePair(name, "c.", ".mcc");
        return chunk.isPresent() && regionKeyOfChunk(chunk.getAsLong()) == key(regionX, regionZ);
    }

    private static OptionalLong parseCoordinatePair(String name, String prefix, String suffix) {
        if (!name.startsWith(prefix) || !name.endsWith(suffix) || name.length() <= prefix.length() + suffix.length()) {
            return OptionalLong.empty();
        }
        String body = name.substring(prefix.length(), name.length() - suffix.length());
        int dot = body.indexOf('.');
        if (dot <= 0 || dot == body.length() - 1 || body.indexOf('.', dot + 1) >= 0) {
            return OptionalLong.empty();
        }
        try {
            int x = Integer.parseInt(body.substring(0, dot));
            int z = Integer.parseInt(body.substring(dot + 1));
            return OptionalLong.of(key(x, z));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Advances the idle timers and returns regions ready to delete, farthest from any keep center
     * first. A region that is not on disk or not eligible this scan restarts its idle timer.
     *
     * @param onDisk    region keys that still have at least one region file
     * @param eligible  outside the keep window, nothing loaded, not already being deleted
     * @param distance  keep-center distance used for ordering (larger deletes first)
     * @param idleTicks continuous eligibility required; 0 makes every eligible region ready
     */
    public long[] plan(
            Collection<Long> onDisk,
            LongPredicate eligible,
            LongToIntFunction distance,
            long nowTick,
            long idleTicks,
            int limit
    ) {
        this.eligibleSince.keySet().removeIf(key -> !onDisk.contains(key));
        List<Long> ready = new ArrayList<>();
        for (long key : onDisk) {
            if (!eligible.test(key)) {
                this.eligibleSince.remove(key);
                continue;
            }
            long since = this.eligibleSince.computeIfAbsent(key, ignored -> nowTick);
            if (nowTick - since >= idleTicks) {
                ready.add(key);
            }
        }
        ready.sort(Comparator.comparingInt((Long key) -> distance.applyAsInt(key)).reversed());
        int count = Math.min(limit, ready.size());
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = ready.get(i);
        }
        return result;
    }

    /** Regions currently counting down their idle timer (ready ones included). */
    public int tracked() {
        return this.eligibleSince.size();
    }

    public void forget(long key) {
        this.eligibleSince.remove(key);
    }

    public void clear() {
        this.eligibleSince.clear();
    }
}
