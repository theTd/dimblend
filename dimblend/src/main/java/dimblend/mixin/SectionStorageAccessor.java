package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Region purge access to section-keyed region storage (POI). Server thread only. */
@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {

    @Accessor("simpleRegionStorage")
    SimpleRegionStorage dimblend$getSimpleRegionStorage();

    /** Cached sections; an {@code Optional.empty()} value memoizes "no data on disk". */
    @Accessor("storage")
    Long2ObjectMap<Optional<?>> dimblend$getStorage();

    /** Sections waiting to be written; a stale key here would rewrite the column after a purge. */
    @Accessor("dirty")
    LongLinkedOpenHashSet dimblend$getDirty();
}
