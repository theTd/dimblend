package dimblend.radio.mixin.client.sodium;

import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the acoustic geometry mirror in sync with the render world: evicts sections that leave
 * the render world, and drops everything when the renderer itself is torn down (world unload,
 * resource reload) — the following rebuild storm repopulates the cache through the tee.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
    @Inject(method = "onSectionRemoved", at = @At("RETURN"))
    private void dimblend$evictAcousticGeometry(int x, int y, int z, CallbackInfo ci) {
        SectionGeometryCache.remove(SectionPos.asLong(x, y, z));
    }

    /**
     * Injected at RETURN: destroy() joins the chunk-builder worker threads (ChunkBuilder.shutdown),
     * so only then is it guaranteed no in-flight tee can repopulate the cache after the clear.
     */
    @Inject(method = "destroy", at = @At("RETURN"))
    private void dimblend$clearAcousticGeometry(CallbackInfo ci) {
        SectionGeometryCache.clear();
    }
}
