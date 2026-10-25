package dimblend.spyglass.server;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.storage.ChunkStorage;

/**
 * Streams only the fields needed to outline a chunk's saved structure starts, and rejects data that
 * cannot belong to the requested chunk.
 *
 * <p>Only chunks saved by the running game version are understood. A chunk with any other
 * {@code DataVersion} is not interpreted and is reported as
 * {@link SkipReason#UNSUPPORTED_DATA_VERSION}; this read path never upgrades saved data.
 *
 * <p>Streaming matters: {@link CollectFields} skips everything that is not selected, so region reads
 * never inflate block data or entities.
 */
final class StructureStartNbt {
    private StructureStartNbt() {
    }

    /** Why a chunk produced no outlines. {@link #NONE} means the chunk was read successfully. */
    enum SkipReason {
        NONE,
        UNSUPPORTED_DATA_VERSION,
        IDENTITY_MISMATCH
    }

    record StartsRead(List<StructureOutline> outlines, SkipReason skipReason) {
    }

    @Nullable
    static CompoundTag readPartial(DataInput input) throws IOException {
        CollectFields fields = new CollectFields(
                new FieldSelector(IntTag.TYPE, "DataVersion"),
                // Identity of the chunk that was actually read. Selecting them lets the scanner
                // reject NBT from a different chunk after a concurrent region rewrite.
                new FieldSelector(IntTag.TYPE, "xPos"),
                new FieldSelector(IntTag.TYPE, "zPos"),
                new FieldSelector("structures", CompoundTag.TYPE, "starts"));
        NbtIo.parse(input, fields, NbtAccounter.unlimitedHeap());
        return fields.getResult() instanceof CompoundTag tag ? tag : null;
    }

    static StartsRead readOutlines(
            DataInput input,
            int targetDataVersion,
            int chunkX,
            int chunkZ) throws IOException {
        CompoundTag partial = readPartial(input);
        if (partial == null) {
            // Not a named compound tag: the stream is corrupt or belongs to something else. The
            // scanner counts this instead of silently reporting "no structures".
            throw new IOException("Chunk data is not a compound NBT tag");
        }
        if (ChunkStorage.getVersion(partial) != targetDataVersion) {
            // Only the running game version's layout is understood; an older or newer chunk would
            // need datafixing, which this read path does not do. Counted, not silently empty.
            return new StartsRead(List.of(), SkipReason.UNSUPPORTED_DATA_VERSION);
        }
        if (!matchesChunk(partial, chunkX, chunkZ)) {
            return new StartsRead(List.of(), SkipReason.IDENTITY_MISMATCH);
        }
        return new StartsRead(parseStarts(partial, chunkX, chunkZ), SkipReason.NONE);
    }

    /**
     * True when the chunk's root {@code xPos}/{@code zPos} match the requested chunk. Both
     * coordinates are mandatory: a chunk without them cannot be trusted to be the requested one.
     */
    static boolean matchesChunk(CompoundTag chunkTag, int chunkX, int chunkZ) {
        return chunkTag.contains("xPos", 3)
                && chunkTag.contains("zPos", 3)
                && chunkTag.getInt("xPos") == chunkX
                && chunkTag.getInt("zPos") == chunkZ;
    }

    /**
     * Reads root {@code structures.starts}, the current chunk format's only location. The saved start
     * is keyed by the chunk it lives in, which is the chunk this tag was read for.
     */
    static List<StructureOutline> parseStarts(CompoundTag chunkTag, int chunkX, int chunkZ) {
        CompoundTag starts = null;
        if (chunkTag.contains("structures", 10)) {
            CompoundTag structures = chunkTag.getCompound("structures");
            if (structures.contains("starts", 10)) {
                starts = structures.getCompound("starts");
            }
        }
        if (starts == null) {
            return List.of();
        }

        List<StructureOutline> outlines = new ArrayList<>();
        for (String key : starts.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null || !starts.contains(key, 10)) {
                continue;
            }
            CompoundTag start = starts.getCompound(key);
            if (start.isEmpty() || "INVALID".equals(start.getString("id"))) {
                continue;
            }
            List<OutlineBox> pieces = readPieces(start);
            if (!pieces.isEmpty()) {
                outlines.add(StructureOutline.of(new StructureKey(id, chunkX, chunkZ), pieces));
            }
        }
        return List.copyOf(outlines);
    }

    private static List<OutlineBox> readPieces(CompoundTag start) {
        ListTag children = start.getList("Children", 10);
        List<OutlineBox> pieces = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            int[] bounds = children.getCompound(i).getIntArray("BB");
            if (bounds.length != 6
                    || bounds[0] > bounds[3]
                    || bounds[1] > bounds[4]
                    || bounds[2] > bounds[5]) {
                continue;
            }
            try {
                pieces.add(new OutlineBox(
                        bounds[0], bounds[1], bounds[2],
                        bounds[3], bounds[4], bounds[5]));
            } catch (IllegalArgumentException outsideWorld) {
                // A piece outside the world border cannot come from a saved start; skip it instead
                // of failing the whole chunk.
            }
        }
        return pieces;
    }
}
