package dimblend.mixin.sable;

import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to per-chunk unloaded sub-level map so purged entries can be removed cleanly. */
@Mixin(SubLevelHoldingChunk.class)
public interface SubLevelHoldingChunkAccessor {

    @Accessor("loadedHoldingSubLevels")
    Object2ObjectMap<UUID, HoldingSubLevel> dimblend$getLoadedHoldingSubLevelsMap();
}
