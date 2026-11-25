package dimblend.experience.mixin.compat.tacz;

import java.nio.ByteBuffer;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.experience.compat.tacz.ScopeAimProgress;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <p>入口另外补 {@code stencilMask(0xFF)} + {@code clearStencil(0)}：三条路径内部都用
 * {@code RenderSystem.clear(GL_STENCIL_BUFFER_BIT)} 清模板，若上游留下 {@code stencilMask == 0}，
 * 那次 {@code glClear} 对模板缓冲完全无效，模板值仍是上一帧的，未开镜时 {@code division}
 * 会命中错误模板值而显形。</p>
 *
 * <p>第二条独立成因：{@code renderOcularAndDivision} 未开镜时 {@code rad == 0}，但第一段的
 * {@code TRIANGLE_FAN} 与第二段的 {@code division}（放大准星 + 黑色镜筒遮罩）<b>照样每帧提交</b>，
 * 全靠模板测试丢弃。当前 FBO 缺模板位（Veil 动态缓冲包装 FBO 只挂 {@code GL_DEPTH_ATTACHMENT}、
 * Iris 手部 GBuffer 无模板）时这些几何就原地糊在镜片上。{@link #dimblend$skipDegenerateScopeOverlay}
 * 在未开镜时把高倍镜分支的退化扇形与 {@code division} 整段跳过，红点/反射分支照旧。</p>
 *
 * <p>目标类不在编译 classpath 上，方法描述符已用 {@code javap -s} 从
 * {@code tacz-neoforge-1.21.1-1.1.8-hotfix-r1.jar} 实读。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.model.BedrockAttachmentModel", remap = false)
public abstract class TaCzScopeStencilCleanupMixin {

    /** {@code 80.0f}：TaCZ 里目镜圆的基准半径；{@code rad = 80 * radiusModifier * aimingProgress}。 */
    @Unique
    private static final float DIMBLEND_SCOPE_RADIUS_BASE = 80.0f;

    /** 视为「完全未开镜」的半径阈值（TaCZ 自己用的也是 {@code 1.0e-4f}）。 */
    @Unique
    private static final float DIMBLEND_NOT_AIMING_EPSILON = 1.0e-4f;

    @Shadow private float scopeViewRadiusModifier;
    @Shadow private List<List<?>> ocularNodePaths;
    @Shadow private List<List<?>> divisionNodePaths;
    @Shadow private List<Boolean> isScopeOcular;

    @Shadow
    private void renderTempPart(PoseStack poseStack, ItemDisplayContext transformType, RenderType renderType,
            int light, int overlay, List<?> path) {
        throw new AssertionError();
    }

    @WrapMethod(
            method = "renderBoth(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderBoth(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        this.dimblend$renderWithStencilGuard(pose, context, renderType, light, overlay, original);
    }

    @WrapMethod(
            method = "renderScope(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderScope(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        this.dimblend$renderWithStencilGuard(pose, context, renderType, light, overlay, original);
    }

    @WrapMethod(
            method = "renderSight(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            require = 1,
            allow = 1)
    private void dimblend$cleanupAfterRenderSight(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        this.dimblend$renderWithStencilGuard(pose, context, renderType, light, overlay, original);
    }

    /**
     * 未开镜时跳过只该在开镜后出现的高倍镜退化扇形与 {@code division}（放大准星 + 黑遮罩），
     * 同时保留常态镜片 {@code ocularNodePaths} 与复合瞄具上的红点分支。
     *
     * <p>判定用 TaCZ 自己算 {@code rad} 的同一份数据（{@code scopeViewRadiusModifier} ×
     * {@code IClientPlayerGunOperator#getClientAimingProgress}）。未开镜
     * （{@code rad <= 1e-4f}）时：先将模板操作复位为 {@code KEEP}；复合瞄具上的红点/反射分支
     * （{@code selective && !isScopeOcular.get(i)}）照原样画 {@code divisionNodePaths.get(i)}，
     * 高倍镜分支（{@code !selective || isScopeOcular.get(i)}）只画常态镜片
     * {@code ocularNodePaths.get(i)}、跳过退化 {@code TRIANGLE_FAN} 与高倍 {@code division}。
     * 正在开镜时原样交给 TaCZ 自己的模板流程（{@code ci.cancel()} 都不触发）。</p>
     */
    @Inject(
            method = "renderOcularAndDivision(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;IIZ)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1)
    private void dimblend$skipDegenerateScopeOverlay(
            PoseStack poseStack, ItemDisplayContext transformType, RenderType renderType, int light, int overlay,
            boolean selective, CallbackInfo ci) {
        if (this.ocularNodePaths.isEmpty()) {
            return;
        }
        float radius = DIMBLEND_SCOPE_RADIUS_BASE * this.scopeViewRadiusModifier * ScopeAimProgress.current();
        if (radius > DIMBLEND_NOT_AIMING_EPSILON) {
            // 正在开镜（或读不到进度，ScopeAimProgress 保守返回 1.0f）：完全交给 TaCZ 自己的模板流程。
            return;
        }
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        int count = Math.min(this.ocularNodePaths.size(), this.divisionNodePaths.size());
        for (int i = 0; i < count; i++) {
            if (i > 127) {
                throw new IllegalArgumentException("Index of oculus is out of range for 127");
            }
            RenderSystem.stencilFunc(GL11.GL_EQUAL, i + 1, 255);
            if (selective && !this.isScopeOcular.get(i)) {
                this.renderTempPart(poseStack, transformType, renderType, light, overlay, this.divisionNodePaths.get(i));
            } else {
                // 高倍镜未开镜：保留常态目镜镜片，仅跳过放大准星与黑色镜筒遮罩（divisionNodePaths）。
                this.renderTempPart(poseStack, transformType, renderType, light, overlay, this.ocularNodePaths.get(i));
            }
        }
        ci.cancel();
    }

    /** 三条路径共用的守卫：入口补模板写掩码，出口双向还原模板开关与颜色写掩码。 */
    @Unique
    private void dimblend$renderWithStencilGuard(
            PoseStack pose, ItemDisplayContext context, RenderType renderType, int light, int overlay,
            Operation<Void> original) {
        boolean stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int colorMask = this.dimblend$sampleColorWriteMask();
        // 入口先保证模板写掩码全开：三条路径内部那次 RenderSystem.clear 才能真正清掉模板。
        RenderSystem.stencilMask(0xFF);
        RenderSystem.clearStencil(0);
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
