package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.nio.file.Path;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Region purge access to the open region file cache; only safe on the owning IO worker thread. */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {

    /** Open region files keyed by {@code ChunkPos.asLong(regionX, regionZ)}. */
    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<RegionFile> dimblend$getRegionCache();

    /** Directory holding {@code r.X.Z.mca} and oversized-chunk {@code c.X.Z.mcc} files. Immutable. */
    @Accessor("folder")
    Path dimblend$getFolder();
}
