package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read access to the chunk-system internals for the chunk-generation stall monitor. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Invoker("getChunks")
    Iterable<ChunkHolder> dimblend$getChunks();

    @Accessor("queueSorter")
    ChunkTaskPriorityQueueSorter dimblend$getQueueSorter();

    /** Holders parked until their chunk is saved and unloaded; size is the unload backlog. */
    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> dimblend$getPendingUnloads();
}