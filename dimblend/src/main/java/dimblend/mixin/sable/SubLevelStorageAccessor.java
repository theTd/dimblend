package dimblend.mixin.sable;

import dev.ryanhcode.sable.sublevel.storage.region.SubLevelRegionFile;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to Sable's open region/storage handle caches so empty files for purged regions can be deleted. */
@Mixin(SubLevelStorage.class)
public interface SubLevelStorageAccessor {

    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<SubLevelRegionFile> dimblend$getRegionCache();

    @Accessor("storageCache")
    Long2ObjectLinkedOpenHashMap<SubLevelStorageFile> dimblend$getStorageCache();
}
