package dimblend.spyglass.server;

import com.mojang.serialization.Codec;
import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;

/**
 * Streams the fields a shape extraction needs out of one saved chunk.
 *
 * <p>Two reads, both read-only: {@link #readStatus} selects only the identity fields and
 * {@code Status} (the {@code sections} payload is skipped by {@link CollectFields}, so a start chunk
 * that never reached {@code features} is rejected without inflating its block data), and
 * {@link #readSections} selects {@code sections} and decodes them with vanilla's own codecs.
 *
 * <p>The block-state codec is the one {@code ChunkSerializer} uses
 * ({@code ChunkSerializer.java:70-72}: {@code PalettedContainer.codecRW} over
 * {@code Block.BLOCK_STATE_REGISTRY}). It is safe off the server thread for the same reason vanilla
 * IO workers can decode chunks there: the registries are frozen and the codec only reads them. The
 * biome codec mirrors {@code ChunkSerializer.makeBiomeCodec} ({@code ChunkSerializer.java:275-279})
 * and resolves holders through the frozen biome registry.
 *
 * <p>Only chunks saved by the running game version are interpreted: a different {@code DataVersion}
 * is reported as {@link SkipReason#UNSUPPORTED_DATA_VERSION} instead of being datafixed, and a chunk
 * whose own {@code xPos}/{@code zPos} differ from the requested one is reported as
 * {@link SkipReason#IDENTITY_MISMATCH}.
 */
final class ChunkShapeNbt {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC,
            PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());

    private ChunkShapeNbt() {
    }

    enum SkipReason {
        NONE,
        UNSUPPORTED_DATA_VERSION,
        IDENTITY_MISMATCH
    }

    /** @param status raw {@code Status} name, {@code null} when the chunk has none */
    record StatusRead(@Nullable String status, SkipReason skipReason) {
    }

    /** One saved chunk section: its section Y, decoded block states and biomes. */
    record Section(int sectionY, PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
    }

    /** @param heightmaps the chunk's raw {@code Heightmaps} compound, {@code null} when it has none */
    record SectionsRead(
            List<Section> sections, @Nullable CompoundTag heightmaps, SkipReason skipReason) {
        SectionsRead {
            sections = List.copyOf(sections);
        }
    }

    static StatusRead readStatus(DataInput input, int targetDataVersion, int chunkX, int chunkZ)
            throws IOException {
        CompoundTag partial = readPartial(input, new FieldSelector(StringTag.TYPE, "Status"));
        if (partial == null) {
            throw new IOException("Chunk data is not a compound NBT tag");
        }
        SkipReason reason = checkChunk(partial, targetDataVersion, chunkX, chunkZ);
        if (reason != SkipReason.NONE) {
            return new StatusRead(null, reason);
        }
        String status = partial.contains("Status", 8) ? partial.getString("Status") : null;
        return new StatusRead(status, SkipReason.NONE);
    }

    static SectionsRead readSections(
            DataInput input, int targetDataVersion, int chunkX, int chunkZ, Registry<Biome> biomeRegistry)
            throws IOException {
        CompoundTag partial = readPartial(
                input,
                new FieldSelector(ListTag.TYPE, "sections"),
                heightmapSelector(ChunkHeightmaps.WORLD_SURFACE),
                heightmapSelector(ChunkHeightmaps.WORLD_SURFACE_WG));
        if (partial == null) {
            throw new IOException("Chunk data is not a compound NBT tag");
        }
        SkipReason reason = checkChunk(partial, targetDataVersion, chunkX, chunkZ);
        if (reason != SkipReason.NONE) {
            return new SectionsRead(List.of(), null, reason);
        }
        return new SectionsRead(
                decodeSections(partial.getList("sections", 10), biomeRegistry),
                heightmaps(partial),
                SkipReason.NONE);
    }

    /**
     * Reads only the surface heightmap of a chunk, plus the identity fields every read verifies.
     * Used for the one-chunk ring around a structure's covering chunks, where no blocks are needed.
     *
     * <p>The status is selected as well: a ring chunk that has not reached
     * {@code minecraft:features} has not generated its terrain yet, so its columns are closed like a
     * missing chunk's (see {@link ChunkHeightmaps}).
     *
     * @return the chunk's {@code Heightmaps} compound, or {@code null} when it has none, is not
     *         generated yet, or is not the requested one / not the current data version
     */
    @Nullable
    static CompoundTag readHeightmaps(DataInput input, int targetDataVersion, int chunkX, int chunkZ)
            throws IOException {
        CompoundTag partial = readPartial(
                input,
                new FieldSelector(StringTag.TYPE, "Status"),
                heightmapSelector(ChunkHeightmaps.WORLD_SURFACE),
                heightmapSelector(ChunkHeightmaps.WORLD_SURFACE_WG));
        if (partial == null) {
            throw new IOException("Chunk data is not a compound NBT tag");
        }
        if (checkChunk(partial, targetDataVersion, chunkX, chunkZ) != SkipReason.NONE) {
            return null;
        }
        String status = partial.contains("Status", 8) ? partial.getString("Status") : null;
        if (!isGenerated(status)) {
            return null;
        }
        return heightmaps(partial);
    }

    private static FieldSelector heightmapSelector(String name) {
        return new FieldSelector(ChunkHeightmaps.HEIGHTMAPS_TAG, LongArrayTag.TYPE, name);
    }

    @Nullable
    private static CompoundTag heightmaps(CompoundTag partial) {
        return partial.contains(ChunkHeightmaps.HEIGHTMAPS_TAG, 10)
                ? partial.getCompound(ChunkHeightmaps.HEIGHTMAPS_TAG)
                : null;
    }

    @Nullable
    private static CompoundTag readPartial(DataInput input, FieldSelector... extras) throws IOException {
        FieldSelector[] selectors = new FieldSelector[extras.length + 3];
        selectors[0] = new FieldSelector(IntTag.TYPE, "DataVersion");
        // Identity of the chunk that was actually read; see ReadOnlyRegionScanner.
        selectors[1] = new FieldSelector(IntTag.TYPE, "xPos");
        selectors[2] = new FieldSelector(IntTag.TYPE, "zPos");
        System.arraycopy(extras, 0, selectors, 3, extras.length);
        CollectFields fields = new CollectFields(selectors);
        NbtIo.parse(input, fields, NbtAccounter.unlimitedHeap());
        return fields.getResult() instanceof CompoundTag tag ? tag : null;
    }

    private static SkipReason checkChunk(CompoundTag tag, int targetDataVersion, int chunkX, int chunkZ) {
        if (ChunkStorage.getVersion(tag) != targetDataVersion) {
            return SkipReason.UNSUPPORTED_DATA_VERSION;
        }
        if (!StructureStartNbt.matchesChunk(tag, chunkX, chunkZ)) {
            return SkipReason.IDENTITY_MISMATCH;
        }
        return SkipReason.NONE;
    }

    private static List<Section> decodeSections(ListTag list, Registry<Biome> biomeRegistry) {
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(),
                biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        List<Section> sections = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            // A section without block data only carries light; it holds no blocks to extract.
            if (!section.contains("block_states", 10)) {
                continue;
            }
            int sectionY = section.getByte("Y");
            PalettedContainer<BlockState> states;
            try {
                states = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                        .getOrThrow(FormatException::new);
            } catch (RuntimeException exception) {
                throw new FormatException("Invalid block_states in section " + sectionY, exception);
            }
            PalettedContainerRO<Holder<Biome>> biomes;
            try {
                biomes = section.contains("biomes", 10)
                        ? biomeCodec.parse(NbtOps.INSTANCE, section.getCompound("biomes"))
                                .getOrThrow(FormatException::new)
                        : new PalettedContainer<>(
                                biomeRegistry.asHolderIdMap(),
                                biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                                PalettedContainer.Strategy.SECTION_BIOMES);
            } catch (RuntimeException exception) {
                throw new FormatException("Invalid biomes in section " + sectionY, exception);
            }
            sections.add(new Section(sectionY, states, biomes));
        }
        return sections;
    }

    /** True when the saved status is at or after {@code minecraft:features}. */
    static boolean isGenerated(@Nullable String statusName) {
        if (statusName == null || statusName.isEmpty()) {
            return false;
        }
        ChunkStatus status = ChunkStatus.byName(statusName);
        return status != null && status.isOrAfter(ChunkStatus.FEATURES);
    }

    /** A decode failure that makes this structure's shape unavailable, never a whole-scan failure. */
    static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message);
        }

        FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
