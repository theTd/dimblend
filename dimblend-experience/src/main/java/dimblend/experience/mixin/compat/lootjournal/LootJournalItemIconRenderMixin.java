package dimblend.experience.mixin.compat.lootjournal;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拾取图标 {@code ItemPickupEvent#renderIcon} 的 GL 状态隔离。
 *
 * <p>图标由 {@code SimpleIcon} 在 {@code RayGlowEffect} 之后绘制。射线特效收尾时会把
 * {@code GL_CULL_FACE} 与 {@code GL_DEPTH_TEST} 重新打开，而
 * {@code GuiGraphics#renderFakeItem} 会对物品模型施加 {@code scale(16, -16, 16)}——Y 轴镜像
 * 翻转三角形绕序，在背面剔除开启时整组 quad 被剔掉，图标就“消失”了。此外瞄准镜/手部渲染
 * 若把 {@code GL_STENCIL_TEST} 连同脏模板值泄漏到 GUI 阶段，图标 quad 同样会被模板测试拒掉。</p>
 *
 * <p>因此绘制期间：关闭模板测试、关闭背面剔除、关闭深度测试，并把 shader color 临时恢复为
 * {@code (1,1,1,alpha)}（保留当前 alpha，图标不因面板淡入的 RGB 调制偏色）；{@code RETURN}
 * 处逐项还原，不改变上游的其它状态。</p>
 *
 * <p>描述符已用 {@code javap -s} 从 {@code loot_journal-neoforge-1.21.1-6.2.2.jar} 实读为
 * {@code (Lnet/minecraft/client/gui/GuiGraphics;Ldev/obscuria/lootjournal/client/renderer/PickupRenderer;)V}。
 * 目标类不在编译 classpath 上，所以用 {@code targets} 字符串 + {@link Pseudo}。</p>
 */
@Pseudo
@Mixin(targets = "dev.obscuria.lootjournal.client.events.ItemPickupEvent", remap = false)
public abstract class LootJournalItemIconRenderMixin {

    @Unique
    private boolean dimblend$stencilWasEnabled;
    @Unique
    private boolean dimblend$cullWasEnabled;
    @Unique
    private boolean dimblend$depthWasEnabled;
    @Unique
    private float dimblend$savedShaderRed;
    @Unique
    private float dimblend$savedShaderGreen;
    @Unique
    private float dimblend$savedShaderBlue;
    @Unique
    private float dimblend$savedShaderAlpha;

    @Inject(
            method = "renderIcon(Lnet/minecraft/client/gui/GuiGraphics;Ldev/obscuria/lootjournal/client/renderer/PickupRenderer;)V",
            at = @At("HEAD"),
            require = 1,
            allow = 1)
    private void dimblend$prepareIconRender(CallbackInfo ci) {
        this.dimblend$stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        if (this.dimblend$stencilWasEnabled) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        this.dimblend$cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if (this.dimblend$cullWasEnabled) {
            RenderSystem.disableCull();
        }
        this.dimblend$depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        if (this.dimblend$depthWasEnabled) {
            RenderSystem.disableDepthTest();
        }
        float[] color = RenderSystem.getShaderColor();
        this.dimblend$savedShaderRed = color[0];
        this.dimblend$savedShaderGreen = color[1];
        this.dimblend$savedShaderBlue = color[2];
        this.dimblend$savedShaderAlpha = color[3];
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, color[3]);
    }

    @Inject(
            method = "renderIcon(Lnet/minecraft/client/gui/GuiGraphics;Ldev/obscuria/lootjournal/client/renderer/PickupRenderer;)V",
            at = @At("RETURN"),
            require = 1,
            allow = 1)
    private void dimblend$restoreIconRenderState(CallbackInfo ci) {
        RenderSystem.setShaderColor(
                this.dimblend$savedShaderRed,
                this.dimblend$savedShaderGreen,
                this.dimblend$savedShaderBlue,
                this.dimblend$savedShaderAlpha);
        if (this.dimblend$depthWasEnabled) {
            RenderSystem.enableDepthTest();
        }
        if (this.dimblend$cullWasEnabled) {
            RenderSystem.enableCull();
        }
        if (this.dimblend$stencilWasEnabled) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        }
    }
}
