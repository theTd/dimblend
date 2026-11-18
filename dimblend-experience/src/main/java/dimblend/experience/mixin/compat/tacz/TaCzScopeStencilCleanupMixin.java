package dimblend.experience.mixin.compat.tacz;

import java.nio.ByteBuffer;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * TaCZ 瞄准镜的三条模板路径（{@code renderBoth} / {@code renderScope} / {@code renderSight}）都会
 * 开启 {@code GL_STENCIL_TEST} 后返回——正常出口会关掉，但加速渲染的 before/after 回调、
 * 附件模型缺失或第三方替换都可能让某条路径提前返回，把模板测试与逐层掩码状态泄漏到后续
 * GUI/手部渲染（Loot Journal 图标、普通物品）上。
 *
 * <p>这里对三条路径统一加 {@code try/finally}：进入时先记录 {@code GL_STENCIL_TEST} 与
 * {@code GL_COLOR_WRITEMASK} 的真实状态（{@code renderOcularStencil} / {@code renderOcularAndDivision}
 * 都会把颜色写掩码切成 {@code (false,false,false,false)} 再恢复成 {@code (true,true,true,true)}，
 * 提前返回时就会把“全禁写”泄漏出去）；{@code finally} 里把模板函数、模板操作、写掩码复位为
 * {@code ALWAYS / KEEP / 0xFF}、清除值复位为 0，然后把模板开关与颜色写掩码<b>双向</b>还原成入口值
 * （入口是开就开、入口是关就关，入口掩码是什么就恢复什么），不把任何一项留给后续绘制。</p>
 *
 * <p>目标类不在编译 classpath 上，方法描述符已用 {@code javap -s} 从
 * {@code tacz-neoforge-1.21.1-1.1.8-hotfix-r1.jar} 实读为
 * {@code (Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V}。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.model.BedrockAttachmentModel", remap = false)
public abstract class TaCzScopeStencilCleanupMixin {

    @WrapMethod(
            method = "renderBoth(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderBoth(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        boolean stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int colorMask = this.dimblend$sampleColorWriteMask();
        try {
            original.call(pose, context, renderType, light, overlay);
        } finally {
            this.dimblend$resetScopeStencilState(stencilWasEnabled, colorMask);
        }
    }

    @WrapMethod(
            method = "renderScope(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderScope(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        boolean stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int colorMask = this.dimblend$sampleColorWriteMask();
        try {
            original.call(pose, context, renderType, light, overlay);
        } finally {
            this.dimblend$resetScopeStencilState(stencilWasEnabled, colorMask);
        }
    }

    @WrapMethod(
            method = "renderSight(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderSight(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        boolean stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int colorMask = this.dimblend$sampleColorWriteMask();
        try {
            original.call(pose, context, renderType, light, overlay);
        } finally {
            this.dimblend$resetScopeStencilState(stencilWasEnabled, colorMask);
        }
    }

    /** 读取 4 个颜色写掩码位到 {@code bit0 = R, bit1 = G, bit2 = B, bit3 = A}，避免堆分配。 */
    @Unique
    private int dimblend$sampleColorWriteMask() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            int bits = 0;
            for (int channel = 0; channel < 4; channel++) {
                if (mask.get(channel) != 0) {
                    bits |= 1 << channel;
                }
            }
            return bits;
        }
    }

    @Unique
    private void dimblend$resetScopeStencilState(boolean stencilWasEnabled, int colorMask) {
        RenderSystem.stencilMask(0xFF);
        RenderSystem.clearStencil(0);
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        if (stencilWasEnabled) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        } else {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        RenderSystem.colorMask(
                (colorMask & 1) != 0,
                (colorMask & 2) != 0,
                (colorMask & 4) != 0,
                (colorMask & 8) != 0);
    }
}
