package dimblend.mixin.sable;

import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to unloaded holding-chunk state in Sable's storage map during region purge. */
@Mixin(SubLevelHoldingChunkMap.class)
public interface SubLevelHoldingChunkMapAccessor {

    @Accessor("allHoldingSubLevels")
    Object2ObjectMap<UUID, HoldingSubLevel> dimblend$getAllHoldingSubLevels();

    @Accessor("loadedHoldingChunks")
    Long2ObjectMap<SubLevelHoldingChunk> dimblend$getLoadedHoldingChunks();

    @Accessor("dirtyHoldingChunks")
    LongSet dimblend$getDirtyHoldingChunks();

    @Accessor("queuedUnloads")
    ObjectSet<ChunkPos> dimblend$getQueuedUnloads();

    @Accessor("queuedDeletion")
    ObjectSet<GlobalSavedSubLevelPointer> dimblend$getQueuedDeletion();
}
