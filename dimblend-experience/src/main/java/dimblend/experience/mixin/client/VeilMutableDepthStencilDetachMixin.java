package dimblend.experience.mixin.client;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.impl.client.render.framebuffer.AdvancedFboMutableTextureAttachment;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Veil 池化动态 FBO 复用：把「纯深度」纹理挂回 {@code GL_DEPTH_ATTACHMENT} 时，
 * 顺带解绑同一 attachment 点上的 {@code GL_STENCIL_ATTACHMENT}。
 *
 * <p>{@code DynamicBufferManager#getDynamicFbo} 会在池里反复复用同一个
 * {@code AdvancedFbo}，并用 {@code setDepthAttachmentTexture} 切换深度纹理。
 * 当上一轮挂的是 {@code GL_DEPTH_STENCIL_ATTACHMENT}（packed 纹理，见
 * {@link VeilWrappedDepthStencilMixin} 的 {@code getAttachmentType} 修正）、
 * 这一轮换成纯深度纹理时，若只重新挂 {@code GL_DEPTH_ATTACHMENT}，
 * 旧的模板挂载点仍留在 FBO 上（{@code GL_STENCIL_BITS} 看似非 0，但模板内容来自上一帧的
 * 另一张纹理），TaCZ 的模板测试就会按陈旧模板值放行/丢弃，表现为随机糊镜。
 * 因此在挂载点是纯深度时显式把 {@code GL_STENCIL_ATTACHMENT} 置 0。</p>
 */
@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.framebuffer.AdvancedFboMutableTextureAttachment", remap = false)
public abstract class VeilMutableDepthStencilDetachMixin {

    @Inject(
            method = "attach(Lfoundry/veil/api/client/render/framebuffer/AdvancedFbo;I)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1)
    private void dimblend$detachStaleStencil(AdvancedFbo framebuffer, int attachment, CallbackInfo ci) {
        AdvancedFboMutableTextureAttachment self = (AdvancedFboMutableTextureAttachment) (Object) this;
        if (self.getAttachmentType() != GL30.GL_DEPTH_ATTACHMENT || attachment != 0) {
            return;
        }
        int framebufferId = framebuffer.getId();
        if (framebufferId <= 0) {
            return;
        }
        if (VeilRenderSystem.directStateAccessSupported()) {
            ARBDirectStateAccess.glNamedFramebufferTexture(
                    framebufferId, GL30.GL_STENCIL_ATTACHMENT, 0, 0);
        } else {
            GL30C.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        }
    }
}
