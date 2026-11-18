package dimblend.experience.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Veil 把自己“第一人称 FBO”当作 {@code renderLevel} 到 {@code renderItemInHand} 之间的绘制目标，
 * 而 {@code VeilFirstPersonRenderer} 传进来的掩码是原版 {@code RenderSystem.clear(16640)} 的
 * {@code GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT}——<b>不含 GL_STENCIL_BUFFER_BIT</b>。
 *
 * <p>TaCZ 的瞄准镜分划只靠模板缓冲分层（{@code GL_GREATER} 写层 + {@code GL_EQUAL} 读层），
 * 只要上一帧/上一次 FBO 重建残留了非零模板值，某些分划层就会整层消失。因此 Legacy 路径
 * （{@code bind} + {@code glClearDepth(depth)} + {@code GlStateManager._clear}）在清深度的同时
 * 补清模板：{@code clearMask} 入口补 {@code GL_STENCIL_BUFFER_BIT}，并在 clear 执行前把
 * {@code glStencilMask} 设为 {@code 0xFF}、清除值设为 0（{@code glClear} 受写掩码约束）；
 * clear 返回后立刻把写掩码与清除值还原成入口值，不把状态泄漏给后续绘制。</p>
 *
 * <p><b>这里只挂 Legacy 两个类。</b>DSA 路径的 {@code clear} 在
 * {@code hasDepth && hasStencil && (mask & (DEPTH|STENCIL)) == (DEPTH|STENCIL)} 时走
 * {@code glClearTexImage(depthStencilTex, 0, GL_DEPTH_STENCIL, GL_FLOAT, null)}，按
 * {@code ARB_clear_texture} 规范 {@code data == null} 会把每个纹素清零（深度 0.0、模板 0），
 * 于是“清深度到 1.0”变成“清深度到 0.0”，第一人称手臂/手持物整批被 {@code GL_LEQUAL} 拒掉。
 * DSA 目标改由 {@link VeilDsaFboStencilClearMixin} 处理（剥掉模板位 + 单独的模板清零）。</p>
 *
 * <p>两个目标的 {@code clear(F...I[I)} 描述符均为 {@code (FFFFFI[I)V}，{@code clearMask} 位于
 * 实参槽 6。返回点有两个（{@code clearMask == 0} 的提前返回 + 正常出口），所以用
 * {@code TAIL}（最后一个 RETURN）而不是 {@code RETURN}：后者会命中两个注入点，
 * {@code allow = 1} 会在首次加载时抛 {@code InjectionError}。</p>
 */
@Pseudo
@Mixin(targets = {
        "foundry.veil.impl.client.render.framebuffer.LegacyAdvancedFboImpl",
        "foundry.veil.impl.client.render.wrapper.LegacyVanillaAdvancedFboWrapper"
}, remap = false)
public abstract class VeilFboStencilClearMixin {

    @Unique
    private boolean dimblend$pendingStencilStateRestore;

    @Unique
    private int dimblend$prevStencilWriteMask;

    @Unique
    private int dimblend$prevClearStencil;

    @ModifyVariable(
            method = "clear(FFFFFI[I)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 6,
            require = 1,
            allow = 1)
    private int dimblend$includeStencilInDepthClear(int clearMask) {
        this.dimblend$pendingStencilStateRestore = false;
        if ((clearMask & GL11.GL_DEPTH_BUFFER_BIT) == 0) {
            return clearMask;
        }
        this.dimblend$prevStencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        this.dimblend$prevClearStencil = GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
        if (this.dimblend$prevStencilWriteMask != 0xFF) {
            RenderSystem.stencilMask(0xFF);
        }
        if (this.dimblend$prevClearStencil != 0) {
            RenderSystem.clearStencil(0);
        }
        this.dimblend$pendingStencilStateRestore = true;
        return clearMask | GL11.GL_STENCIL_BUFFER_BIT;
    }

    @Inject(
            method = "clear(FFFFFI[I)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1)
    private void dimblend$restoreStencilClearState(CallbackInfo ci) {
        if (!this.dimblend$pendingStencilStateRestore) {
            return;
        }
        this.dimblend$pendingStencilStateRestore = false;
        if (this.dimblend$prevStencilWriteMask != 0xFF) {
            RenderSystem.stencilMask(this.dimblend$prevStencilWriteMask);
        }
        if (this.dimblend$prevClearStencil != 0) {
            RenderSystem.clearStencil(this.dimblend$prevClearStencil);
        }
    }
}
