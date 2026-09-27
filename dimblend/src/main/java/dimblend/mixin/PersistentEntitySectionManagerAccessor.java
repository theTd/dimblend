package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Region purge view of entity chunk state; entities unload on their own schedule, apart from chunks. */
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor {

    /** Non-FRESH entity chunks (PENDING load or LOADED); an absent key means nothing in memory. */
    @Accessor("chunkLoadStatuses")
    Long2ObjectMap<?> dimblend$getChunkLoadStatuses();

    /** Chunks whose entities are queued to be saved and unloaded. */
    @Accessor("chunksToUnload")
    LongSet dimblend$getChunksToUnload();

    /**
     * Live entity sections. An entity can create a section in a FRESH chunk (e.g. moving into
     * it); autosave then writes that chunk, so such chunks count as busy too.
     */
    @Accessor("sectionStorage")
    EntitySectionStorage<?> dimblend$getSectionStorage();

    @Accessor("permanentStorage")
    EntityPersistentStorage<?> dimblend$getPermanentStorage();
}
