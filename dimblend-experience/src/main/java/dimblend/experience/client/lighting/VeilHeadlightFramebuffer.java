package dimblend.experience.client.lighting;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.framebuffer.FramebufferAttachmentDefinition;
import foundry.veil.api.client.render.framebuffer.FramebufferDefinition;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Matches Veil's depth blit to NeoForge's stencil-enabled main target (e.g. TACZ). */
final class VeilHeadlightFramebuffer {
    private static final ResourceLocation LIGHT = ResourceLocation.fromNamespaceAndPath("veil", "light");
    private static FramebufferManager manager;
    private static FramebufferDefinition original;
    private static FramebufferDefinition adjusted;

    private VeilHeadlightFramebuffer() {}

    static void synchronize() {
        var currentManager = VeilRenderSystem.renderer().getFramebufferManager();
        var definition = currentManager.getFramebufferDefinition(LIGHT);
        if (currentManager != manager || definition != adjusted) {
            manager = currentManager;
            original = definition;
            adjusted = null;
        }
        if (!Minecraft.getInstance().getMainRenderTarget().isStencilEnabled()) {
            restore();
            return;
        }
        if (definition == null || definition.depthBuffer() == null
                || definition.depthBuffer().format() == FramebufferAttachmentDefinition.Format.DEPTH32F_STENCIL8) return;
        var depth = definition.depthBuffer();
        var replacement = new FramebufferAttachmentDefinition(depth.type(),
                FramebufferAttachmentDefinition.Format.DEPTH32F_STENCIL8, true,
                depth.filter(), depth.levels(), depth.name());
        adjusted = new FramebufferDefinition(definition.width(), definition.height(),
                definition.colorBuffers(), replacement, definition.autoClear());
        manager.setDefinition(LIGHT, adjusted);
    }

    static void restore() {
        // Do not overwrite a resource reload or another mod's subsequent definition.
        if (manager != null && original != null && adjusted != null
                && manager.getFramebufferDefinition(LIGHT) == adjusted) manager.setDefinition(LIGHT, original);
        manager = null;
        original = null;
        adjusted = null;
    }
}
