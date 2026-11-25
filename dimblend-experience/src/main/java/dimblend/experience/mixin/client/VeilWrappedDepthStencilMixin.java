package dimblend.experience.mixin.client;

import dimblend.experience.client.headlight.VeilWrappedDepthFormat;
import foundry.veil.api.client.render.framebuffer.AdvancedFboTextureAttachment;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.lwjgl.opengl.GL30;

/** Veil 4.3.2 must recognize stencil before choosing its wrapped framebuffer clear format. */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.framebuffer.AdvancedFboTextureAttachment", remap = false)
public abstract class VeilWrappedDepthStencilMixin {
    @Shadow @Final private int attachmentType;
    @Shadow @Final private int internalFormat;

    @Inject(method = "getFormat()I", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$resolveWrappedDepthFormat(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() == 0) {
            int texture = ((AdvancedFboTextureAttachment) (Object) this).getId();
            cir.setReturnValue(VeilWrappedDepthFormat.resolve(attachmentType, texture));
        }
    }

    /**
     * 包装外部纹理（{@code internalFormat == 0}）时，把 packed depth-stencil 的挂载点改报成
     * {@code GL_DEPTH_STENCIL_ATTACHMENT (33306)}。
     *
     * <p>Veil 的 {@code AdvancedFbo.Builder#setDepthTextureWrapper} 硬编码按
     * {@code GL_DEPTH_ATTACHMENT (36096)} 建 {@code AdvancedFboMutableTextureAttachment}；
     * 按 OpenGL 4.6 §9.2.8，把 {@code GL_DEPTH32F_STENCIL8} 纹理挂到 {@code GL_DEPTH_ATTACHMENT}
     * 只挂深度分量，FBO 的 {@code GL_STENCIL_ATTACHMENT} 仍是 {@code GL_NONE}
     * （{@code GL_STENCIL_BITS == 0}）——此时开启 {@code GL_STENCIL_TEST} 的模板测试恒视为通过，
     * TaCZ 未开镜时提交的 {@code division} 准星/黑遮罩就直接糊在镜片上。</p>
     *
     * <p>{@code AdvancedFboMutableTextureAttachment#attach} 用的是
     * {@code this.getAttachmentType()}（而非构造期字段），所以这里改返回值即可让它挂到
     * {@code GL_DEPTH_STENCIL_ATTACHMENT}；它自己的校验 {@code attachmentType < 36096}
     * 对 {@code 33306} 天然放行。</p>
     */
    @Inject(method = "getAttachmentType()I", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$resolveWrappedAttachmentType(CallbackInfoReturnable<Integer> cir) {
        if (internalFormat != 0) {
            // 真实创建的纹理：挂载点在构造期已给出，不参与包装纹理的修正。
            return;
        }
        if (cir.getReturnValueI() != GL30.GL_DEPTH_ATTACHMENT) {
            return;
        }
        int texture = ((AdvancedFboTextureAttachment) (Object) this).getId();
        if (VeilWrappedDepthFormat.isDepthStencil(VeilWrappedDepthFormat.resolve(attachmentType, texture))) {
            cir.setReturnValue(GL30.GL_DEPTH_STENCIL_ATTACHMENT);
        }
    }

    @ModifyArgs(method = "create()V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL30C;glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V"), require = 1, allow = 1)
    private void dimblend$packedDepthStencilUpload(Args args) {
        // Veil's first-person FBO owns a packed depth texture when main has stencil.
        int format = args.get(2);
        if (format == GL30.GL_DEPTH32F_STENCIL8) {
            args.set(6, GL30.GL_DEPTH_STENCIL);
            args.set(7, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV);
        } else if (format == GL30.GL_DEPTH24_STENCIL8) {
            args.set(6, GL30.GL_DEPTH_STENCIL);
            args.set(7, GL30.GL_UNSIGNED_INT_24_8);
        }
    }
}
