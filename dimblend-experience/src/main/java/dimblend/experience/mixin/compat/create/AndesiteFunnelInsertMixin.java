package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;

import dimblend.experience.compat.create.AndesiteFunnelRedstoneRules;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 安山漏斗"能不能塞东西"忽略 {@code POWERED}（2026-11-25 legacy 兜底）。
 *
 * <p>{@code FunnelBlock#canInsertIntoFunnel} 原本是 {@code POWERED == false && EXTRACTING == false}。
 * 安山漏斗的 POWERED 已经永远为 false（见 {@code AndesiteFunnelRedstoneMixin}），
 * 这里的覆写只为"旧存档第一 tick、POWERED 还没来得及复位"的场景：
 * 此时也不该把漏斗当成暂停态，否则玩家第一 tick 手动塞入会莫名失败。</p>
 */
@Mixin(FunnelBlock.class)
public abstract class AndesiteFunnelInsertMixin {

    @Inject(method = "canInsertIntoFunnel", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$ignorePoweredForInsert(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!AndesiteFunnelRedstoneRules.isAndesiteFunnel(state)) {
            return;
        }
        boolean extracting = state.hasProperty(FunnelBlock.EXTRACTING)
                && state.getValue(FunnelBlock.EXTRACTING);
        cir.setReturnValue(!extracting);
    }
}
