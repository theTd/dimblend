package dimblend.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderAccessor {
    /** Last status that passed {@code acquireStatusBump}; null before any step starts. */
    @Accessor("startedWork")
    AtomicReference<ChunkStatus> dimblend$startedWork();

    /**
     * Per-status generation futures, indexed by {@link ChunkStatus#getIndex()}; a null slot
     * means no future exists for that status. Read in place so the monitor scan does not
     * allocate the {@code List<Pair<...>>} that {@code getAllFutures()} builds per holder.
     */
    @Accessor("futures")
    AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> dimblend$futures();
}
