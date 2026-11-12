package dimblend.experience.compat.lootjournal;

import java.util.Arrays;

import com.mojang.blaze3d.systems.RenderSystem;
import dimblend.experience.mixin.client.GuiGraphicsBufferSourceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * 让 Loot Journal 的拾取提示在「HUD 批次化」环境里走原版立即绘制路径。
 *
 * <h2>根因（字节码已核）</h2>
 * <p>ImmediatelyFast 的 {@code hud_batching} 打开时，{@code GuiGraphics#bufferSource} 被替换成
 * {@code HudBatchingBufferSource}。它把 {@code ItemRenderer#render} 包进
 * {@code WrappedRenderLayer}：绘制时<b>重新开启深度测试</b>并写到 {@code z = +230}；而原版
 * {@code GuiGraphics#flush()} 走 {@code disableDepthTest()} 的立即绘制，图标与文字在同一个
 * 「无深度测试」窗口里叠加。Loot Journal 的图标是
 * {@code GuiGraphics#renderFakeItem(stack, -8, -8)}（vanilla {@code GuiGraphics.java:792-797}），
 * 于是同一批 HUD 里 z 更大的背景一画完，图标就被深度测试挡掉——<b>文字在、图标不在</b>。</p>
 *
 * <h2>做法</h2>
 * <p>只在这一个 HUD 组件自己的绘制窗口内处理，不改 ImmediatelyFast 的配置、不动其它 HUD：</p>
 * <ol>
 *   <li>先把此前已排队的批次刷出去（{@code graphics.flush()}），保证换 source 时队列干净；</li>
 *   <li>把 {@code bufferSource} 临时换成原版 {@code RenderBuffers#bufferSource()}，
 *       使该组件内部的物品图标走原版 {@code disableDepthTest()} 路径；</li>
 *   <li>颜色/混合状态按原值进出：进入时强制 {@code (1,1,1,1)} + {@code defaultBlendFunc()}，
 *       退出时恢复进入前的 {@code getShaderColor()}、{@code defaultBlendFunc()} 与深度测试；</li>
 *   <li>{@code finally} 里把 {@code bufferSource} 换回 ImmediatelyFast 那一个——即便绘制抛异常，
 *       后续 HUD 也不会拿着原版 source 继续画。</li>
 * </ol>
 *
 * <p>ImmediatelyFast 缺席时（{@code previousSource == vanillaSource}）不需要换 source，只把
 * 绘制与颜色/混合状态按同一口径包好，行为与原版等价。</p>
 *
 * <p>本类不属于 {@code dimblend.experience.mixin} 包（Mixin 包内只放 mixin 类），只引用
 * 已注册的 {@link GuiGraphicsBufferSourceAccessor} 访问器接口。</p>
 */
public final class LootJournalHudCompat {

    /** 换 source 时统一的颜色：ImmediatelyFast 的批次会覆盖 {@code ColorModulator}，这里显式拉回满色。 */
    private static final float[] OPAQUE = {1.0F, 1.0F, 1.0F, 1.0F};

    /** 保存进入绘制窗口前的缓冲区、颜色与深度测试状态，供 {@link #endUnbatched} 在 finally 中对称恢复。 */
    public record UnbatchedScope(
            MultiBufferSource.BufferSource previousSource,
            boolean swappedSource,
            float[] previousColor,
            boolean previousDepthTest) {
    }

    /**
     * 进入「无 HUD 批次」绘制窗口（供 {@code @WrapMethod} 无 lambda 调用，避免在 Mixin 类内生成合成静态方法）。
     */
    public static UnbatchedScope beginUnbatched(GuiGraphics graphics) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || graphics == null) {
            return null;
        }
        final MultiBufferSource.BufferSource vanillaSource = minecraft.renderBuffers().bufferSource();
        final MultiBufferSource.BufferSource previousSource = graphics.bufferSource();
        final float[] previousColor = Arrays.copyOf(RenderSystem.getShaderColor(), 4);
        final boolean previousDepthTest = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        if (previousSource == vanillaSource) {
            return new UnbatchedScope(previousSource, false, previousColor, previousDepthTest);
        }
        graphics.flush();
        ((GuiGraphicsBufferSourceAccessor) graphics).dimblend$setBufferSource(vanillaSource);
        RenderSystem.setShaderColor(OPAQUE[0], OPAQUE[1], OPAQUE[2], OPAQUE[3]);
        RenderSystem.defaultBlendFunc();
        return new UnbatchedScope(previousSource, true, previousColor, previousDepthTest);
    }

    /**
     * 退出「无 HUD 批次」绘制窗口（必须在 {@code finally} 中与 {@link #beginUnbatched} 配对调用）。
     */
    public static void endUnbatched(GuiGraphics graphics, UnbatchedScope scope) {
        if (scope == null || graphics == null) {
            return;
        }
        try {
            graphics.flush();
        } finally {
            RenderSystem.setShaderColor(
                    scope.previousColor()[0],
                    scope.previousColor()[1],
                    scope.previousColor()[2],
                    scope.previousColor()[3]);
            RenderSystem.defaultBlendFunc();
            if (scope.previousDepthTest()) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            if (scope.swappedSource()) {
                ((GuiGraphicsBufferSourceAccessor) graphics).dimblend$setBufferSource(scope.previousSource());
            }
        }
    }

    /**
     * 在「无 HUD 批次」的等价状态下执行一次绘制调用。
     *
     * @param graphics 组件拿到的 {@link GuiGraphics}
     * @param drawCall 该组件自己的绘制体（内部读 {@code graphics.bufferSource()}）
     */
    public static void renderUnbatched(GuiGraphics graphics, Runnable drawCall) {
        final UnbatchedScope scope = beginUnbatched(graphics);
        try {
            drawCall.run();
        } finally {
            endUnbatched(graphics, scope);
        }
    }

    private LootJournalHudCompat() {
    }
}
