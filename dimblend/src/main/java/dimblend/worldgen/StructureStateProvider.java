package dimblend.worldgen;

import java.util.List;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

/**
 * Exposes the per-band structure states of a generator that splits the world into bands, together
 * with the chunk range each state owns (see {@link StructureStateBand}).
 *
 * <h2>Threading contract</h2>
 * <p>{@link #structureStatesFor} <b>must be called on the server thread</b>. Besides resolving the
 * band layout (which depends on the generator's runtime seed/layout state), it calls
 * {@link ChunkGeneratorStructureState#ensureStructuresGenerated()} on every returned state.
 * Vanilla only does that for the main generator state, on the server thread right after level
 * construction ({@code ServerLevel.java:266}); a banded generator has several extra states that
 * vanilla never initializes, and {@code ensureStructuresGenerated()} is unsynchronized
 * ({@code ChunkGeneratorStructureState.java:170-175}) while it fills
 * {@code placementsForStructure} and kicks off the concentric-ring biome searches
 * ({@code ChunkGeneratorStructureState.java:81-106}). Leaving that to the first caller would both
 * stall the caller and race any worldgen worker using the same state.
 *
 * <p>After this method has returned, the states may be read from another thread: the plain reads
 * {@code getPlacementsForStructure} ({@code ChunkGeneratorStructureState.java:184-187}),
 * {@code getRingPositionsFor} ({@code ChunkGeneratorStructureState.java:177-182}),
 * {@code StructurePlacement.isStructureChunk} ({@code StructurePlacement.java:82-86}),
 * {@code RandomSpreadStructurePlacement.getPotentialStructureChunk}
 * ({@code RandomSpreadStructurePlacement.java:70-79}) and
 * {@code ConcentricRingsStructurePlacement.isPlacementChunk}
 * ({@code ConcentricRingsStructurePlacement.java:83-87}) then hit already-populated maps/futures.
 * The {@code ensureStructuresGenerated()} guard inside them is a plain boolean read after the first
 * call, so nothing mutates the state any more; the caller must merely publish this call's result to
 * the reading thread through a happens-before edge (e.g. submitting to an executor after the call).
 *
 * <p>Do <b>not</b> call these accessors on a state that was not handed out by this method: the
 * first {@code getPlacementsForStructure}/{@code getRingPositionsFor} would mutate the state on the
 * calling thread.
 */
public interface StructureStateProvider {
    /**
     * Returns one entry per band delegate that owns any chunk in the given inclusive chunk window,
     * each restricted to the sub-window it owns. Never returns a state without having ensured its
     * structure placements are generated.
     */
    List<StructureStateBand> structureStatesFor(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);
}
