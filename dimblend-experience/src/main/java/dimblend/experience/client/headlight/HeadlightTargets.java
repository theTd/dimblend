package dimblend.experience.client.headlight;

import com.mojang.blaze3d.pipeline.RenderTarget;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** A scene snapshot and a color-only view of main: sampling depth/dynamic buffers cannot feed back. */
final class HeadlightTargets implements AutoCloseable {
    private AdvancedFbo scene;
    private AdvancedFbo output;
    private int mainColor = -1;
    private AdvancedFbo bufferClear;
    private int albedoTexture;
    private int normalTexture;
    private AdvancedFbo fog;
    private AdvancedFbo opaque;

    void prepareDynamicBuffers(RenderTarget main) {
        // Veil normally clears at endFrame; freshly allocated/resized textures have no previous frame.
        // This is the same lookup used by the program's dynamic-buffer texture sources (Veil 4.3.2).
        var manager = VeilRenderSystem.renderer().getDynamicBufferManger();
        int albedo = manager.getBufferTexture(DynamicBufferType.ALBEDO);
        int normal = manager.getBufferTexture(DynamicBufferType.NORMAL);
        if (bufferClear != null && bufferClear.getWidth() == main.width && bufferClear.getHeight() == main.height
                && albedoTexture == albedo && normalTexture == normal) return;
        try (var ignored = new HeadlightRenderState()) {
            if (bufferClear != null) bufferClear.free();
            bufferClear = AdvancedFbo.withSize(main.width, main.height)
                    .addColorTextureWrapper(albedo).addColorTextureWrapper(normal)
                    .setDebugLabel("dimblend headlight buffer initialization").build(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            RenderSystem.colorMask(true, true, true, true);
            bufferClear.clear(0, 0, 0, 0, GL11.GL_COLOR_BUFFER_BIT);
        }
        albedoTexture = albedo;
        normalTexture = normal;
    }

    void prepare(RenderTarget main) {
        if (scene == null || scene.getWidth() != main.width || scene.getHeight() != main.height
                || mainColor != main.getColorTextureId()) {
            closeComposite();
            scene = AdvancedFbo.withSize(main.width, main.height)
                    .addColorTextureBuffer().setDebugLabel("dimblend headlight scene").build(true);
            output = AdvancedFbo.withSize(main.width, main.height)
                    .addColorTextureWrapper(main.getColorTextureId())
                    .setDebugLabel("dimblend headlight composite").build(true);
            mainColor = main.getColorTextureId();
        }
        // Both FBOs have only attachment 0. Neither allocates, copies, nor interprets main's stencil.
        output.resolveToAdvancedFbo(scene, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }

    int sceneTexture() { return scene.getColorTextureAttachment(0).getId(); }

    void bindOutput() { output.bind(true); }

    void clearOutput() { output.clear(0, 0, 0, 0, GL11.GL_COLOR_BUFFER_BIT); }

    void bindFog(RenderTarget main) {
        ensureFog(main);
        // Every texel is overwritten by the fullscreen pass, including ray misses.
        fog.bind(true);
    }

    /** Half-resolution opaque scene distance, captured before translucent terrain writes its depth. */
    void bindOpaque(RenderTarget main) {
        ensureFog(main);
        opaque.bind(true);
    }

    private void ensureFog(RenderTarget main) {
        int width = Math.max(1, (main.width + 1) / 2);
        int height = Math.max(1, (main.height + 1) / 2);
        if (fog == null || fog.getWidth() != width || fog.getHeight() != height) {
            closeFog();
            // RGB = scatter; A = scene distance used by the march. No depth/stencil attachment.
            fog = AdvancedFbo.withSize(width, height).setFormat(GL11.GL_RGBA, GL30.GL_RGBA16F)
                    .addColorTextureBuffer().setDebugLabel("dimblend headlight fog").build(true);
            opaque = AdvancedFbo.withSize(width, height).setFormat(GL11.GL_RED, GL30.GL_R32F)
                    .addColorTextureBuffer().setDebugLabel("dimblend headlight opaque distance").build(true);
        }
    }

    int opaqueTexture() { return opaque.getColorTextureAttachment(0).getId(); }

    int fogTexture() { return fog.getColorTextureAttachment(0).getId(); }

    int fogWidth() { return fog.getWidth(); }

    int fogHeight() { return fog.getHeight(); }

    void closeFog() {
        if (fog != null) fog.free();
        if (opaque != null) opaque.free();
        fog = null;
        opaque = null;
    }

    @Override
    public void close() {
        closeComposite();
        closeFog();
        if (bufferClear != null) bufferClear.free();
        bufferClear = null;
    }

    private void closeComposite() {
        if (output != null) output.free();
        if (scene != null) scene.free();
        output = null;
        scene = null;
        mainColor = -1;
    }
}
