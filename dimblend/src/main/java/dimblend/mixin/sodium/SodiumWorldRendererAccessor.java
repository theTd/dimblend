package dimblend.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code SodiumWorldRenderer.renderSectionManager} so the far pass can call
 * {@code RenderSectionManager.renderLayer} directly.
 *
 * <p>The method name is deliberately unique: Veil already adds a {@code getRenderSectionManager}
 * accessor to the same field, and matching it would be a duplicate-method mixin crash. Never route
 * the second pass through {@code SodiumWorldRenderer.drawChunkLayer} — Sable injects its
 * sub-level draw at that method's TAIL, which would render every train carriage twice per frame.</p>
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumWorldRendererAccessor {

    @Accessor("renderSectionManager")
    RenderSectionManager dimblend$getRenderSectionManager();
}
