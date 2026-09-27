package dimblend.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the IO worker behind the entity and POI region storages for region purge. */
@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {

    @Accessor("worker")
    IOWorker dimblend$getWorker();
}
