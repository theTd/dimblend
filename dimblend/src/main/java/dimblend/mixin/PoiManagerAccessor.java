package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Region purge access to POI chunk bookkeeping. Server thread only. */
@Mixin(PoiManager.class)
public interface PoiManagerAccessor {

    /**
     * Chunks already block-scanned by {@code ensureLoadedAndValid}. A purged chunk left here
     * would skip the rescan after regeneration and lose its POIs (beds, bells, portals).
     */
    @Accessor("loadedChunks")
    LongSet dimblend$getLoadedChunks();

    /** Re-evaluates the village distance tracker for a section, as vanilla does on section load. */
    @Invoker("onSectionLoad")
    void dimblend$onSectionLoad(long sectionKey);
}
