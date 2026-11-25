package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;

import dimblend.experience.compat.create.AndesiteFunnelRedstoneRules;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 安山漏斗放置时强制 {@code POWERED = false}（2026-11-25）。
 *
 * <p>Create 6.0.10 里 {@code getStateForPlacement} 有三份实现：
 * {@link AbstractFunnelBlock}（写 {@code hasNeighborSignal}）、{@link FunnelBlock}（把 super 的结果
 * 逐方向复制、顺带复制 POWERED）、{@link BeltFunnelBlock}（传送带形态）。三份都声明了同名同描述符方法，
 * 所以一个三目标 mixin、每份一个 {@code RETURN} 注入即可全覆盖（{@code require=1, allow=1} 对每个目标各钉一次）。</p>
 *
 * <p>只对安山漏斗生效；黄铜漏斗（含黄铜传送带漏斗）的放置行为完全不动。</p>
 */
@Mixin({AbstractFunnelBlock.class, FunnelBlock.class, BeltFunnelBlock.class})
public abstract class AndesiteFunnelPlacementMixin {

    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true, require = 1, allow = 2)
    private void dimblend$forceUnpoweredOnPlacement(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state == null) {
            return;
        }
        cir.setReturnValue(AndesiteFunnelRedstoneRules.withUnpowered(state));
    }
}
