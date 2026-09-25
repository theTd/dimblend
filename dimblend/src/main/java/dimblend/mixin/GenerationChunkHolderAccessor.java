package dimblend.mixin;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderAccessor {
    /** Last status that passed {@code acquireStatusBump}; null before any step starts. */
    @Accessor("startedWork")
    AtomicReference<ChunkStatus> dimblend$startedWork();
}
