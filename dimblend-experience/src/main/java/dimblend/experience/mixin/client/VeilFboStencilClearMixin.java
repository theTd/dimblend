package dimblend.experience.mixin.client;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Veil 把自己的“第一人称 FBO”当作 {@code renderLevel} 到 {@code renderItemInHand} 之间的绘制目标，
 * 而 {@code VeilFirstPersonRenderer.bind(mask)} 传进来的掩码是原版 {@code RenderSystem.clear(16640)}
 * 的 {@code GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT}——<b>不含 GL_STENCIL_BUFFER_BIT</b>。
 *
 * <p>TaCZ 的瞄准镜分划只靠模板缓冲分层（{@code GL_GREATER} 写层 + {@code GL_EQUAL} 读层），
 * 只要上一帧/上一次 FBO 重建残留了非零模板值，某些分划层就会整层消失——正是“偶发显示错误”。
 * 因此凡是清深度的 FBO clear，都把模板一起清成 0：{@code clear(F...I[I)} 的 {@code clearMask}
 * 入口统一补 {@code GL_STENCIL_BUFFER_BIT}，并把 {@code glStencilMask} 设为 {@code 0xFF}
 * （{@code glClear} 受写掩码约束）、清除值设为 0。没有模板附件的 FBO 上该位是 GL 规范里的
 * 合法 no-op，所以不需要读取目标类的继承字段/方法（这些类都不在本 mixin 的 target 上自声明
 * {@code hasStencilAttachment()}）。</p>
 *
 * <p>四个目标覆盖 Veil 的两种 FBO 实现（Legacy/DSA）与两种主渲染目标包装器；它们的
 * {@code clear(F...I[I)} 方法描述符均为 {@code (FFFFFI[I)V}，{@code clearMask} 位于局部变量 6。</p>
 */
@Pseudo
@Mixin(targets = {
        "foundry.veil.impl.client.render.framebuffer.LegacyAdvancedFboImpl",
        "foundry.veil.impl.client.render.framebuffer.DSAAdvancedFboImpl",
        "foundry.veil.impl.client.render.wrapper.LegacyVanillaAdvancedFboWrapper",
        "foundry.veil.impl.client.render.wrapper.DSAVanillaAdvancedFboWrapper"
}, remap = false)
public abstract class VeilFboStencilClearMixin {

    @ModifyVariable(
            method = "clear(FFFFFI[I)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 6,
            require = 1,
            allow = 1)
    private int dimblend$includeStencilInDepthClear(int clearMask) {
        if ((clearMask & GL11.GL_DEPTH_BUFFER_BIT) == 0
                || (clearMask & GL11.GL_STENCIL_BUFFER_BIT) != 0) {
            return clearMask;
        }
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(0);
        return clearMask | GL11.GL_STENCIL_BUFFER_BIT;
    }
}
