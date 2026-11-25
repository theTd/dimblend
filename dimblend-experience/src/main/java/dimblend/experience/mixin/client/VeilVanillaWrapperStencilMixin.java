package dimblend.experience.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import foundry.veil.impl.client.render.wrapper.VanillaAdvancedFboWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code VanillaAdvancedFboWrapper#hasStencilAttachment()} 只按
 * {@code depthTextureId} 缓存 {@code hasStencil}，查询时机早于 {@code RenderTarget#enableStencil()}
 * 时会永久缓存 {@code false}：TaCZ 在 {@code ClientSetupEvent} 里
 * {@code recordRenderCall(() -> mainRenderTarget.enableStencil())}，随后原版重建深度纹理时
 * 复用了同一个 GL 纹理 ID，缓存键（int id）不变，于是 Veil 一直认为主 FBO 没有模板位，
 * 清屏只清 color+depth，模板缓冲残留上一帧内容。
 *
 * <p>这里在拿到 {@code false} 时再用 {@code RenderTarget#isStencilEnabled()}（同一份权威状态位，
 * 不受 GL 纹理 ID 复用影响）兜底：只要原版自己认为要模板，就报 {@code true}。</p>
 */
@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.wrapper.VanillaAdvancedFboWrapper", remap = false)
public abstract class VeilVanillaWrapperStencilMixin {

    @Inject(method = "hasStencilAttachment()Z", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$honorStencilFlag(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        RenderTarget target = ((VanillaAdvancedFboWrapper) (Object) this).toRenderTarget();
        if (target != null && target.isStencilEnabled()) {
            cir.setReturnValue(true);
        }
    }
}
