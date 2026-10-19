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

    @Inject(method = "getFormat()I", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$resolveWrappedDepthFormat(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() == 0) {
            int texture = ((AdvancedFboTextureAttachment) (Object) this).getId();
            cir.setReturnValue(VeilWrappedDepthFormat.resolve(attachmentType, texture));
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
