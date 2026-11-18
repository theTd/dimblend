package dimblend.experience.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DSA 路径的 Veil FBO 清屏修正：修复“第一人称看不到自己”（手臂/副手/手持物整体消失）。
 *
 * <p>Veil 4.3.2 的 {@code VeilFirstPersonRenderer} 用 {@code AdvancedFbo#clear(GL_DEPTH_BUFFER_BIT)}
 * 清第一人称 FBO，其默认重载把 {@code depth = 1.0f} 传给
 * {@code clear(float,float,float,float,float,int,int...)}。而
 * {@code DSAAdvancedFboImpl#clear} / {@code DSAVanillaAdvancedFboWrapper#clear} 在
 * {@code hasStencil && (mask & DEPTH) != 0 && (mask & STENCIL) != 0} 时调用
 * {@code ARBClearTexture.glClearTexImage(depthStencilTex, 0, GL_DEPTH_STENCIL, GL_FLOAT, null)}；
 * 按 {@code ARB_clear_texture} 规范，{@code data == null} 表示“用全零填充每个纹素”，
 * 于是 {@code depth = 1.0f} 被完全忽略，深度缓冲变成 <b>0.0f</b>（近裁剪面）。
 * 之后 {@code renderItemInHand} 里所有片元都过不了 {@code glDepthFunc(GL_LEQUAL)}，第一人称模型全灭。</p>
 *
 * <p>修法：只要调用方带了深度位，就在入口把 {@code GL_STENCIL_BUFFER_BIT} 从掩码里剥掉
 * （即使 {@code AdvancedFboImpl.Wrapper#clear} 这类调用方传了 {@code 0x4500} 也一样），
 * 让 Veil 走 {@code glClearNamedFramebufferfv(id, GL_DEPTH, 0, floats(depth))} 正确清成 1.0；
 * 模板缓冲的需求（TaCZ 分划层不能吃上一帧残留值）改在 {@code TAIL} 里用
 * {@code glClearNamedFramebufferiv(id, GL_STENCIL, 0, ints(0))} 单独清 0，
 * 清完把 {@code glStencilMask} 还原成入口值。</p>
 *
 * <p>两个目标类在 {@code clear(F...I[I)} 里都有多个 RETURN（{@code clearMask == 0} 提前返回 +
 * “既无深度也无模板”提前返回 + 正常出口，DSA 实现为 3 个），{@code @At("RETURN")} 配
 * {@code allow = 1} 会在首次加载时抛 {@code InjectionError}，所以统一用 {@code TAIL}（最后一个 RETURN）。</p>
 *
 * <p>目标类不在编译期 classpath 上（Veil 是 {@code compileOnly}），用 {@code targets} 字符串 +
 * {@link Pseudo}；但 {@code AdvancedFbo}、{@code ARBDirectStateAccess}、{@code MemoryStack}
 * 都在 classpath 上，可直接按接口读取 {@code hasStencilAttachment()} / {@code getId()}。</p>
 */
@Pseudo
@Mixin(targets = {
        "foundry.veil.impl.client.render.framebuffer.DSAAdvancedFboImpl",
        "foundry.veil.impl.client.render.wrapper.DSAVanillaAdvancedFboWrapper"
}, remap = false)
public abstract class VeilDsaFboStencilClearMixin {

    @Unique
    private boolean dimblend$pendingStencilClear;

    @ModifyVariable(
            method = "clear(FFFFFI[I)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 6,
            require = 1,
            allow = 1)
    private int dimblend$stripStencilFromDepthClear(int clearMask) {
        if ((clearMask & GL11.GL_DEPTH_BUFFER_BIT) == 0) {
            this.dimblend$pendingStencilClear = false;
            return clearMask;
        }
        this.dimblend$pendingStencilClear = true;
        return clearMask & ~GL11.GL_STENCIL_BUFFER_BIT;
    }

    @Inject(
            method = "clear(FFFFFI[I)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1)
    private void dimblend$clearStencilToZero(CallbackInfo ci) {
        if (!this.dimblend$pendingStencilClear) {
            return;
        }
        this.dimblend$pendingStencilClear = false;
        AdvancedFbo self = (AdvancedFbo) (Object) this;
        if (!self.hasStencilAttachment() || self.getId() <= 0) {
            return;
        }
        int prevWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        boolean restoreWriteMask = prevWriteMask != 0xFF;
        if (restoreWriteMask) {
            RenderSystem.stencilMask(0xFF);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ARBDirectStateAccess.glClearNamedFramebufferiv(
                    self.getId(), GL11.GL_STENCIL, 0, stack.ints(0));
        } finally {
            if (restoreWriteMask) {
                RenderSystem.stencilMask(prevWriteMask);
            }
        }
    }
}
