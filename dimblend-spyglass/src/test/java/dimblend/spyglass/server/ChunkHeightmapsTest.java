package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import org.junit.jupiter.api.Test;

/**
 * Decoding tests for the saved {@code WORLD_SURFACE} heightmap. The fixture writes the same raw
 * layout vanilla writes ({@code Heightmap#getRawData}: {@link SimpleBitStorage} with
 * {@code Mth.ceillog2(levelHeight + 1)} bits, 256 entries, value = Y - minBuildHeight), so a change in
 * either the bit width or the index order breaks these tests.
 */
class ChunkHeightmapsTest {
    private static final int MIN_BUILD_HEIGHT = -64;
    private static final int LEVEL_HEIGHT = 384;

    @Test
    void decodesTheVanillaRawLayoutColumnByColumn() {
        int[] surfaces = new int[256];
        for (int index = 0; index < surfaces.length; index++) {
            surfaces[index] = -60 + index;
        }
        ChunkHeightmaps heightmaps = new ChunkHeightmaps(MIN_BUILD_HEIGHT, LEVEL_HEIGHT);

        assertTrue(heightmaps.put(3, -2, surface(surfaces)));

        assertEquals(1, heightmaps.storedChunks());
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(
                        surfaces[x + z * 16],
                        heightmaps.height(3 * 16 + x, -2 * 16 + z),
                        "column " + x + "," + z);
            }
        }
    }

    @Test
    void fallsBackToTheWorldgenMap() {
        CompoundTag maps = new CompoundTag();
        int bits = Mth.ceillog2(LEVEL_HEIGHT + 1);
        SimpleBitStorage storage = new SimpleBitStorage(bits, 256);
        for (int index = 0; index < 256; index++) {
            storage.set(index, 200 - MIN_BUILD_HEIGHT);
        }
        maps.put("WORLD_SURFACE_WG", new LongArrayTag(storage.getRaw()));
        ChunkHeightmaps heightmaps = new ChunkHeightmaps(MIN_BUILD_HEIGHT, LEVEL_HEIGHT);

        assertTrue(heightmaps.put(0, 0, maps));
        assertEquals(200, heightmaps.height(0, 0));
    }

    @Test
    void aMissingOrMalformedMapClosesEveryColumnOfTheChunk() {
        ChunkHeightmaps heightmaps = new ChunkHeightmaps(MIN_BUILD_HEIGHT, LEVEL_HEIGHT);
        assertFalse(heightmaps.put(0, 0, null));
        assertFalse(heightmaps.put(0, 0, new CompoundTag()));

        CompoundTag wrongLength = new CompoundTag();
        wrongLength.put("WORLD_SURFACE", new LongArrayTag(new long[] {1L, 2L}));
        assertFalse(heightmaps.put(0, 0, wrongLength));

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(ChunkHeightmaps.CLOSED, heightmaps.height(x, z));
            }
        }
    }

    @Test
    void chunksThatWereNotReadHaveClosedColumns() {
        ChunkHeightmaps heightmaps = new ChunkHeightmaps(MIN_BUILD_HEIGHT, LEVEL_HEIGHT);

        assertEquals(ChunkHeightmaps.CLOSED, heightmaps.height(1000, -1000));
        assertEquals(0, heightmaps.storedChunks());
    }

    @Test
    void aValueOutsideTheDimensionClosesOnlyThatColumn() {
        // Re-encode with a value one past the maximum build height (corrupt data).
        int bits = Mth.ceillog2(LEVEL_HEIGHT + 1);
        SimpleBitStorage storage = new SimpleBitStorage(bits, 256);
        for (int index = 0; index < 256; index++) {
            storage.set(index, index == 5 ? LEVEL_HEIGHT + 1 : 100 - MIN_BUILD_HEIGHT);
        }
        CompoundTag maps = new CompoundTag();
        maps.put("WORLD_SURFACE", new LongArrayTag(storage.getRaw()));
        ChunkHeightmaps heightmaps = new ChunkHeightmaps(MIN_BUILD_HEIGHT, LEVEL_HEIGHT);

        assertTrue(heightmaps.put(0, 0, maps));
        assertEquals(ChunkHeightmaps.CLOSED, heightmaps.height(5, 0));
        assertEquals(100, heightmaps.height(6, 0));
    }

    private static CompoundTag surface(int[] surfaces) {
        int bits = Mth.ceillog2(LEVEL_HEIGHT + 1);
        SimpleBitStorage storage = new SimpleBitStorage(bits, 256);
        for (int index = 0; index < surfaces.length; index++) {
            storage.set(index, surfaces[index] - MIN_BUILD_HEIGHT);
        }
        CompoundTag maps = new CompoundTag();
        maps.put("WORLD_SURFACE", new LongArrayTag(storage.getRaw()));
        return maps;
    }
}
