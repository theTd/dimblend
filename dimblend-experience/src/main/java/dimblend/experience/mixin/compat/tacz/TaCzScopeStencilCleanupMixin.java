package dimblend.experience.mixin.compat.tacz;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * TaCZ 瞄准镜的三条模板路径（{@code renderBoth} / {@code renderScope} / {@code renderSight}）都会
 * 开启 {@code GL_STENCIL_TEST} 后返回——正常出口会关掉，但加速渲染的 before/after 回调、
 * 附件模型缺失或第三方替换都可能让某条路径提前返回，把模板测试与逐层掩码状态泄漏到后续
 * GUI/手部渲染（Loot Journal 图标、普通物品）上。
 *
 * <p>这里对三条路径统一加 {@code try/finally}：无论正常返回还是异常，都把模板函数、模板操作、
 * 写掩码复位为 {@code ALWAYS / KEEP / 0xFF}，关闭模板测试，并恢复颜色写掩码——这是 TaCZ
 * 自己 {@code RenderHelper.disableItemEntityStencilTest()} 之后仍缺少的那部分清理。</p>
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
        try {
            original.call(pose, context, renderType, light, overlay);
        } finally {
            this.dimblend$resetScopeStencilState();
        }
    }

    @WrapMethod(
            method = "renderScope(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderScope(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        try {
            original.call(pose, context, renderType, light, overlay);
        } finally {
            this.dimblend$resetScopeStencilState();
        }
    }

    @WrapMethod(
            method = "renderSight(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderSight(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        try {
            original.call(pose, context, renderType, light, overlay);
        } finally {
            this.dimblend$resetScopeStencilState();
        }
    }

    @Unique
    private void dimblend$resetScopeStencilState() {
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(0);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.colorMask(true, true, true, true);
    }
}
