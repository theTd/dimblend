package dimblend.experience.mixin.client.compat.cca;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mrh0.createaddition.energy.WireType;
import com.mrh0.createaddition.rendering.WireNodeRenderer;
import dimblend.experience.compat.cca.client.CrossSableWireRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 跨 Sable 子层级接线器线缆改为 2px × 2px 方柱（用户 2026-11-27 口径：粗细同加压管道，材质不变）。
 *
 * <p>{@code wireRender} 是 CCA 所有线缆的唯一落笔点（原版连接、sable compat 重投影后的连接、
 * 手持线缆都走它）。这里在 HEAD 问一次 {@link CrossSableWireRenderer#tryRenderCrossSable}：
 * 只有两端分属不同 Sable 载具时才 cancel 并画方柱；其它情况（真实世界↔载具、同一载具、
 * 手持线缆对真实世界玩家）原版渲染原样执行。</p>
 *
 * <p>用 {@code @Inject(HEAD, cancellable = true)} 而非改写原方法：CCA 原版的两条 ribbon
 * 仍保留给非跨层级线缆，跨层级也无需拆 {@code wirePart} 的内部循环。</p>
 */
@Mixin(value = WireNodeRenderer.class, remap = false)
public abstract class CrossSableWireThicknessMixin {

    @Inject(method = "wireRender", at = @At("HEAD"), cancellable = true, require = 1)
    private static void dimblend$thickenCrossSableWire(BlockEntity tileEntityIn, BlockPos other,
                                                       PoseStack stack, MultiBufferSource buffer,
                                                       float x, float y, float z, WireType type,
                                                       float dis, CallbackInfo ci) {
        if (CrossSableWireRenderer.tryRenderCrossSable(tileEntityIn, other, stack, buffer,
                x, y, z, type, dis)) {
            ci.cancel();
        }
    }
}
