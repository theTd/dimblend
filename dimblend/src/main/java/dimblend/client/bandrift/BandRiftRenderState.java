package dimblend.client.bandrift;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Narrow GL state guard for the two band-rift passes.
 *
 * <p>{@code AFTER_TRANSLUCENT_BLOCKS} fires from inside {@code LevelRenderer.renderSectionLayer}
 * with the translucent render type's state still applied, and {@code AFTER_LEVEL} fires right
 * before the first-person hand pass. Both call sites keep drawing after we return, so every piece
 * of state either pass touches is captured here and restored verbatim — including the raw program
 * id, the vertex array, the texture binding of unit 0 and the separate alpha blend equation, which
 * {@link RenderSystem} does not track.</p>
 *
 * <p>Frame buffers are <b>never cleared</b> here: only the colour attachment is blitted, by
 * {@code GL_COLOR_BUFFER_BIT}. Clearing depth and stencil together on a Veil-managed target is the
 * known depth-zero trap.</p>
 */
final class BandRiftRenderState implements AutoCloseable {
    private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int destRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int destAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
    private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int[] viewport = new int[4];
    private final int[] colorMask = new int[4];
    private final ShaderInstance shader = RenderSystem.getShader();

    BandRiftRenderState() {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, this.colorMask);
    }

    /** Translucent world geometry: depth-tested, no depth write, alpha blended, no culling. */
    void applyMembrane() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.enableDepthTest();
        GlStateManager._depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableCull();
    }

    /** Full-screen colour pass: no depth, no blending (alpha comes straight from the shader). */
    void applyGlitch() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
    }

    @Override
    public void close() {
        if (this.depthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(this.depthMask);
        GlStateManager._depthFunc(this.depthFunc);
        RenderSystem.colorMask(this.colorMask[0] != 0, this.colorMask[1] != 0, this.colorMask[2] != 0, this.colorMask[3] != 0);
        if (this.blend) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        RenderSystem.blendFuncSeparate(this.sourceRgb, this.destRgb, this.sourceAlpha, this.destAlpha);
        RenderSystem.blendEquation(this.equationRgb);
        GL20.glBlendEquationSeparate(this.equationRgb, this.equationAlpha);
        if (this.cullFace) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        if (this.stencil) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        } else {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        if (this.scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(this.textureBinding);
        RenderSystem.activeTexture(previousActive);
        if (this.shader != null) {
            RenderSystem.setShader(() -> this.shader);
        }
        GlStateManager._glUseProgram(this.program);
        GlStateManager._glBindVertexArray(this.vertexArray);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFramebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFramebuffer);
        RenderSystem.viewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
    }
}
