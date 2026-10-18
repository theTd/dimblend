package dimblend.experience.mixin.compat.dynlight;

import dimblend.experience.client.lighting.VeilWrappedDepthFormat;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Veil 4.3.2 allocates depth-stencil textures with depth-only/byte upload arguments. */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.framebuffer.AdvancedFboTextureAttachment", remap = false)
public abstract class VeilDepthStencilTextureMixin {
    @Shadow @Final private int attachmentType;
    @Shadow public abstract int getId();

    @Inject(method = "getFormat", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$resolveWrappedDepthFormat(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() == 0
                && (attachmentType == GL30.GL_DEPTH_ATTACHMENT || attachmentType == GL30.GL_DEPTH_STENCIL_ATTACHMENT)) {
            cir.setReturnValue(VeilWrappedDepthFormat.resolve(0, attachmentType, getId()));
        }
    }

    @ModifyArgs(method = "create", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL30C;glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V"), require = 1, allow = 1)
    private void dimblend$packedDepthStencilUpload(Args args) {
        int internalFormat = args.get(2);
        if (internalFormat == GL30.GL_DEPTH32F_STENCIL8) {
            args.set(6, GL30.GL_DEPTH_STENCIL);
            args.set(7, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV);
        } else if (internalFormat == GL30.GL_DEPTH24_STENCIL8) {
            args.set(6, GL30.GL_DEPTH_STENCIL);
            args.set(7, GL30.GL_UNSIGNED_INT_24_8);
        }
    }
}
