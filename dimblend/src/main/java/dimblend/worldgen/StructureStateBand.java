package dimblend.worldgen;

import java.util.Objects;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

/**
 * One structure state of a banded generator, paired with the inclusive chunk range it owns.
 *
 * <p>Band consumers (e.g. the dimblend-spyglass scanner) must only enumerate structure
 * placements inside {@link #minChunkX()}–{@link #maxChunkX()} for this state: neighbouring
 * bands belong to other delegates and have their own structure sets.
 */
public record StructureStateBand(
        ChunkGeneratorStructureState state,
        int minChunkX,
        int minChunkZ,
        int maxChunkX,
        int maxChunkZ) {
    public StructureStateBand {
        Objects.requireNonNull(state, "state");
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException("Inverted chunk range");
        }
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= this.minChunkX && chunkX <= this.maxChunkX
                && chunkZ >= this.minChunkZ && chunkZ <= this.maxChunkZ;
    }
}
