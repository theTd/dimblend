package dimblend.experience.client.lighting;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Resolves Veil's unknown wrapped depth format without changing texture binding state. */
public final class VeilWrappedDepthFormat {
    private VeilWrappedDepthFormat() {}

    public static int resolve(int declaredFormat, int attachmentType, int texture) {
        if (declaredFormat != 0 || texture == 0
                || (attachmentType != GL30.GL_DEPTH_ATTACHMENT
                && attachmentType != GL30.GL_DEPTH_STENCIL_ATTACHMENT)) return declaredFormat;
        RenderSystem.assertOnRenderThreadOrInit();
        if (VeilRenderSystem.directStateAccessSupported()) {
            return ARBDirectStateAccess.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        }
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture);
        try {
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        } finally {
            GlStateManager._bindTexture(previous);
        }
    }
}
