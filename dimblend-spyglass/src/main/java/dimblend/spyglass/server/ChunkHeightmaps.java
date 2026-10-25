package dimblend.spyglass.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;

/**
 * The {@code WORLD_SURFACE} heightmap of every chunk around one structure, as a world-Y column
 * surface for {@link BuriedBlockFilter}.
 *
 * <p>Format ({@code Heightmap.java:31-36,131-137}, {@code ChunkSerializer.java:213-225,382-388}):
 * {@code Heightmaps} holds named {@code long[]}s laid out exactly like vanilla's raw heightmap data,
 * i.e. a {@link SimpleBitStorage} with {@code bits = Mth.ceillog2(levelHeight + 1)} and 256 entries
 * indexed {@code x + z * 16}. A stored value is the height above the minimum build height, and
 * {@code value + minBuildHeight} is the first Y above the column's topmost non-air block
 * ({@code Heightmap.java:111-117}, predicate {@code NOT_AIR} at {@code :25,140-141} — water counts,
 * so a submerged column's surface is the water surface). {@code WORLD_SURFACE} is the client-usage
 * map saved by the chunk status the scan requires; {@code WORLD_SURFACE_WG} is the worldgen fallback
 * kept for intermediate chunks.
 *
 * <p>A column whose chunk or heightmap could not be read is {@link #CLOSED}: no cell of it is open
 * sky. That is deliberately conservative — a missing ring chunk closes the columns it covers instead
 * of pretending they are clear, so a structure is never shown as exposed through unknown terrain.
 *
 * <p>Scan thread only.
 */
final class ChunkHeightmaps implements BuriedBlockFilter.ColumnSurface {
    static final String WORLD_SURFACE = "WORLD_SURFACE";
    static final String WORLD_SURFACE_WG = "WORLD_SURFACE_WG";
    static final String HEIGHTMAPS_TAG = "Heightmaps";
    /** No cell of the column is open sky. */
    static final int CLOSED = Integer.MAX_VALUE;
    private static final int COLUMNS = 16 * 16;

    private final int minBuildHeight;
    private final int levelHeight;
    private final int bits;
    private final int expectedLongs;
    private final Long2ObjectOpenHashMap<int[]> byChunk = new Long2ObjectOpenHashMap<>();

    ChunkHeightmaps(int minBuildHeight, int levelHeight) {
        this.minBuildHeight = minBuildHeight;
        this.levelHeight = levelHeight;
        this.bits = Mth.ceillog2(levelHeight + 1);
        int valuesPerLong = 64 / this.bits;
        this.expectedLongs = (COLUMNS + valuesPerLong - 1) / valuesPerLong;
    }

    /**
     * Decodes and stores one chunk's surface map.
     *
     * @param heightmaps the chunk's {@code Heightmaps} compound, or {@code null} when the chunk has
     *                   none
     * @return false when the map is missing or malformed; the chunk's columns stay {@link #CLOSED}
     */
    boolean put(int chunkX, int chunkZ, @Nullable CompoundTag heightmaps) {
        if (heightmaps == null) {
            return false;
        }
        LongArrayTag surface = longArray(heightmaps, WORLD_SURFACE);
        if (surface == null) {
            surface = longArray(heightmaps, WORLD_SURFACE_WG);
        }
        if (surface == null) {
            return false;
        }
        long[] raw = surface.getAsLongArray();
        if (raw.length != this.expectedLongs) {
            // A truncated or foreign array cannot be interpreted; vanilla would ignore it too
            // (Heightmap.java:119-128).
            return false;
        }
        int[] columns = new int[COLUMNS];
        try {
            SimpleBitStorage storage = new SimpleBitStorage(this.bits, COLUMNS, raw);
            for (int index = 0; index < COLUMNS; index++) {
                int height = storage.get(index) + this.minBuildHeight;
                columns[index] = height >= this.minBuildHeight
                                && height <= this.minBuildHeight + this.levelHeight
                        ? height
                        : CLOSED;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        this.byChunk.put(ChunkPos.asLong(chunkX, chunkZ), columns);
        return true;
    }

    @Override
    public int height(int x, int z) {
        int[] columns = this.byChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (columns == null) {
            return CLOSED;
        }
        return columns[(x & 15) + (z & 15) * 16];
    }

    int storedChunks() {
        return this.byChunk.size();
    }

    @Nullable
    private static LongArrayTag longArray(CompoundTag heightmaps, String key) {
        return heightmaps.get(key) instanceof LongArrayTag array ? array : null;
    }
}
