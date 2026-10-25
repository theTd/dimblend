package dimblend.spyglass.test;

import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Builds saved-chunk NBT for the shape tests, using the same codecs the game writes with.
 *
 * <p>Positions use section-local indices: block {@code x + (z << 4) + (y << 8)} (4096 per section),
 * biome cell {@code x + (z << 2) + (y << 4)} (64 per section). Section NBT is exactly what
 * {@code ChunkSerializer.write} produces: {@code Y}, {@code block_states}, {@code biomes}.
 *
 * <p>Every chunk also gets a flat {@code WORLD_SURFACE} heightmap at {@link #DEFAULT_SURFACE_Y}, so
 * shape extraction's visibility filter sees open sky above Y = -1 (the structure sits on the
 * surface). Tests that care about burial set their own surface with {@link #flatSurface} or
 * {@link #surface}.
 */
public final class ChunkNbtFixture {
    /** Target data version of the fixtures; the scan only reads chunks saved with this version. */
    public static final int DATA_VERSION = 4000;
    public static final int SECTION_VOLUME = 16 * 16 * 16;
    public static final int BIOME_CELLS = 4 * 4 * 4;
    /** Overworld dimensions, matching {@code RegionReadContext} in the extractor tests. */
    public static final int MIN_BUILD_HEIGHT = -64;
    public static final int LEVEL_HEIGHT = 384;
    /** Heightmap of a chunk without an explicit surface: the topmost non-air block is at Y = -1. */
    public static final int DEFAULT_SURFACE_Y = 0;

    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC,
            PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());

    private ChunkNbtFixture() {
    }

    public static CompoundTag chunk(int dataVersion, int chunkX, int chunkZ, String status) {
        return chunk(dataVersion, chunkX, chunkZ, status, new ListTag());
    }

    public static CompoundTag chunk(
            int dataVersion, int chunkX, int chunkZ, String status, ListTag sections) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", dataVersion);
        chunk.putInt("xPos", chunkX);
        chunk.putInt("zPos", chunkZ);
        chunk.putString("Status", status);
        chunk.put("sections", sections);
        chunk.put("Heightmaps", heightmaps(flatColumns(DEFAULT_SURFACE_Y)));
        return chunk;
    }

    /** Sets a flat surface: {@code H(x, z) = surfaceY} for every column of this chunk. */
    public static void flatSurface(CompoundTag chunk, int surfaceY) {
        chunk.put("Heightmaps", heightmaps(flatColumns(surfaceY)));
    }

    /**
     * Sets the surface per column, indexed {@code x + z * 16} like the vanilla raw layout.
     *
     * @param surfaceHeights first Y above each column's topmost non-air block
     */
    public static void surface(CompoundTag chunk, int[] surfaceHeights) {
        if (surfaceHeights.length != 256) {
            throw new IllegalArgumentException("Expected 256 column heights, got " + surfaceHeights.length);
        }
        chunk.put("Heightmaps", heightmaps(surfaceHeights));
    }

    /**
     * Encodes a surface map the way {@code Heightmap#getRawData} does: a {@link SimpleBitStorage} of
     * {@code Mth.ceillog2(levelHeight + 1)} bits, 256 entries, value = Y - minBuildHeight.
     */
    private static CompoundTag heightmaps(int[] surfaceHeights) {
        int bits = Mth.ceillog2(LEVEL_HEIGHT + 1);
        SimpleBitStorage storage = new SimpleBitStorage(bits, 256);
        for (int index = 0; index < 256; index++) {
            storage.set(index, surfaceHeights[index] - MIN_BUILD_HEIGHT);
        }
        CompoundTag maps = new CompoundTag();
        maps.put("WORLD_SURFACE", new LongArrayTag(storage.getRaw()));
        return maps;
    }

    private static int[] flatColumns(int surfaceY) {
        int[] columns = new int[256];
        java.util.Arrays.fill(columns, surfaceY);
        return columns;
    }

    public static void addSection(CompoundTag chunk, CompoundTag section) {
        ListTag sections = chunk.getList("sections", 10);
        sections.add(section);
        chunk.put("sections", sections);
    }

    public static CompoundTag section(
            int sectionY,
            PalettedContainer<BlockState> states,
            PalettedContainerRO<Holder<Biome>> biomes,
            Registry<Biome> biomeRegistry) {
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sectionY);
        section.put(
                "block_states",
                BLOCK_STATE_CODEC.encodeStart(NbtOps.INSTANCE, states).getOrThrow());
        section.put(
                "biomes",
                biomeCodec(biomeRegistry).encodeStart(NbtOps.INSTANCE, biomes).getOrThrow());
        return section;
    }

    public static PalettedContainer<BlockState> blockStates() {
        return new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
    }

    /** A section container filled with one state; encodes to a single-palette tag. */
    public static PalettedContainer<BlockState> singleBlockState(BlockState state) {
        return new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                state,
                PalettedContainer.Strategy.SECTION_STATES);
    }

    public static void setBlock(PalettedContainer<BlockState> states, int localIndex, BlockState state) {
        int x = localIndex & 15;
        int z = (localIndex >> 4) & 15;
        int y = (localIndex >> 8) & 15;
        states.set(x, y, z, state);
    }

    /** Fills the whole section with one state. */
    public static PalettedContainer<BlockState> filledBlockStates(BlockState state) {
        return singleBlockState(state);
    }

    public static PalettedContainer<Holder<Biome>> biomes(Registry<Biome> registry, ResourceKey<Biome> fill) {
        PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(
                registry.asHolderIdMap(),
                registry.getHolderOrThrow(fill),
                PalettedContainer.Strategy.SECTION_BIOMES);
        fillBiome(biomes, registry, fill);
        return biomes;
    }

    public static void setBiomeCell(
            PalettedContainer<Holder<Biome>> biomes,
            Registry<Biome> registry,
            int cellIndex,
            ResourceKey<Biome> biome) {
        int x = cellIndex & 3;
        int z = (cellIndex >> 2) & 3;
        int y = (cellIndex >> 4) & 3;
        biomes.set(x, y, z, registry.getHolderOrThrow(biome));
    }

    /** Fills every biome cell of the section. */
    public static void fillBiome(
            PalettedContainer<Holder<Biome>> biomes,
            Registry<Biome> registry,
            ResourceKey<Biome> biome) {
        for (int cell = 0; cell < BIOME_CELLS; cell++) {
            setBiomeCell(biomes, registry, cell, biome);
        }
    }

    /** Adds a saved structure start with the given piece boxes ({@code [minX,minY,minZ,maxX,maxY,maxZ]}). */
    public static void addStart(CompoundTag chunk, ResourceLocation id, List<int[]> pieceBoxes) {
        CompoundTag structures = chunk.contains("structures", 10)
                ? chunk.getCompound("structures")
                : new CompoundTag();
        CompoundTag starts = structures.contains("starts", 10)
                ? structures.getCompound("starts")
                : new CompoundTag();
        CompoundTag start = new CompoundTag();
        start.putString("id", id.toString());
        ListTag children = new ListTag();
        for (int[] box : pieceBoxes) {
            CompoundTag piece = new CompoundTag();
            piece.putIntArray("BB", box);
            children.add(piece);
        }
        start.put("Children", children);
        starts.put(id.toString(), start);
        structures.put("starts", starts);
        chunk.put("structures", structures);
    }

    private static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec(Registry<Biome> registry) {
        return PalettedContainer.codecRO(
                registry.asHolderIdMap(),
                registry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                registry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
    }
}
