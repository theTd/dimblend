package dimblend.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the region storage behind entity chunks so region purge can use its IO worker. */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {

    @Accessor("simpleRegionStorage")
    SimpleRegionStorage dimblend$getSimpleRegionStorage();
}
