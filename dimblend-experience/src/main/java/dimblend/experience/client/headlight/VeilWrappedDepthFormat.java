package dimblend.experience.client.headlight;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Resolves Veil's unknown wrapped depth format without changing texture binding state. */
public final class VeilWrappedDepthFormat {
    /** {@code GL_DEPTH_STENCIL}：packed depth-stencil 的 pixel format（Veil 自己的判定集合之一）。 */
    public static final int GL_FORMAT_DEPTH_STENCIL = 34041;
    /** {@code GL_DEPTH24_STENCIL8}：packed depth-stencil 的内部格式。 */
    public static final int GL_INTERNAL_DEPTH24_STENCIL8 = 35056;
    /** {@code GL_DEPTH32F_STENCIL8}：packed depth-stencil 的内部格式。 */
    public static final int GL_INTERNAL_DEPTH32F_STENCIL8 = 36013;

    /**
     * 是否为带模板分量的 packed depth-stencil 格式。
     *
     * <p>与 Veil {@code AdvancedFboImpl}/{@code VanillaAdvancedFboWrapper} 自身判定用的
     * 三个常量保持一致：只有这三种格式才真正挂载了 {@code GL_STENCIL_ATTACHMENT}。</p>
     */
    public static boolean isDepthStencil(int format) {
        return format == GL_FORMAT_DEPTH_STENCIL
                || format == GL_INTERNAL_DEPTH24_STENCIL8
                || format == GL_INTERNAL_DEPTH32F_STENCIL8;
    }

    public static int resolve(int attachmentType, int texture) {
        if (texture == 0 || (attachmentType != GL30.GL_DEPTH_ATTACHMENT
                && attachmentType != GL30.GL_DEPTH_STENCIL_ATTACHMENT)) return 0;
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

    private VeilWrappedDepthFormat() {}
}
