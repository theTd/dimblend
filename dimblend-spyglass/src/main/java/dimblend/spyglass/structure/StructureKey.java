package dimblend.spyglass.structure;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Stable identity of one saved structure start: the structure id plus the chunk the start was saved
 * in.
 *
 * <p>Structure type alone is not unique: the same structure can be saved many times in one world,
 * and the client caches per-structure data (generated shape) that must not mix two villages up. The
 * start chunk is unique per structure type in a chunk: {@code structures.starts} is a map keyed by
 * structure id inside each chunk ({@code ChunkSerializer.java:442-452}), and a chunk only holds the
 * start of a structure whose placement put it there. The key is carried in
 * {@link StructureOutline}, so outlines and streamed shapes can be linked on both sides.
 *
 * <p>Both coordinates are bounded like outline coordinates: a chunk coordinate beyond
 * {@link OutlineBox#MAX_COORDINATE} {@code >> 4} cannot come from a saved structure start and is
 * rejected instead of letting later arithmetic overflow.
 */
public record StructureKey(ResourceLocation id, int startChunkX, int startChunkZ) {
    /** Largest supported chunk coordinate; keeps block coordinates inside an {@code int}. */
    public static final int MAX_CHUNK_COORDINATE = OutlineBox.MAX_COORDINATE >> 4;

    public StructureKey {
        Objects.requireNonNull(id, "id");
        requireChunkCoordinate(startChunkX);
        requireChunkCoordinate(startChunkZ);
    }

    private static void requireChunkCoordinate(int chunk) {
        if (chunk < -MAX_CHUNK_COORDINATE || chunk > MAX_CHUNK_COORDINATE) {
            throw new IllegalArgumentException("Structure start chunk outside the world: " + chunk);
        }
    }
}
