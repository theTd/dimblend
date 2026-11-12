package dimblend.experience.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link GuiGraphics} 的 {@code bufferSource} 写口，只给 Loot Journal 的「拆批次绘制」用。
 *
 * <p>ImmediatelyFast 的 {@code hud_batching} 打开时，{@code GuiGraphics} 的 {@code bufferSource}
 * 被换成 {@code HudBatchingBufferSource}：它把 {@code ItemRenderer#render} 包进
 * {@code WrappedRenderLayer}，在 {@code z = +230} 处<b>重新开启深度测试</b>再画，而原版
 * {@code GuiGraphics#flush()} 走的是 {@code disableDepthTest()} 的立即绘制路径。同一批 HUD 里
 * 先画了 z 更大的背景后，图标就被深度测试挡掉。</p>
 *
 * <p>字段在 vanilla 里是 {@code private final}（{@code GuiGraphics.java:65}，构造器唯一赋值），
 * 所以换引用必须带 {@link Mutable}。这里只在本模组自己的绘制窗口内临时换回原版
 * {@code RenderBuffers#bufferSource()}，退出时必定换回原对象（见
 * {@code dimblend.experience.compat.lootjournal.LootJournalHudCompat}）。</p>
 */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsBufferSourceAccessor {

    @Mutable
    @Accessor("bufferSource")
    void dimblend$setBufferSource(MultiBufferSource.BufferSource bufferSource);
}
