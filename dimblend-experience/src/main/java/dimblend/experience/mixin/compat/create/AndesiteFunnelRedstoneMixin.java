package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;

import dimblend.experience.compat.create.AndesiteFunnelRedstoneRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 安山漏斗红石免疫（Create 6.0.10 字节码基线，2026-11-25）：注入在
 * {@link AbstractFunnelBlock} 自己的 {@code neighborChanged}/{@code tick} 上
 * （{@code FunnelBlock}/{@code BeltFunnelBlock} 都<b>不</b>覆写这两个方法，所以子类自动继承到）。
 *
 * <ul>
 * <li>{@code neighborChanged}：仍然<b>原样</b>调 {@code InvManipulationBehaviour#onNeighborChanged(fromPos)}
 * ——那是漏斗对相邻容器变化的反应（与红石无关，必须保留），然后<b>不再排</b>
 * 1 tick 的红石同步；只有旧存档残留 {@code POWERED == true} 时才排一次 1 tick 让 {@code tick} 复位。</li>
 * <li>{@code tick}：只做 legacy 复位（{@code POWERED true → false}），然后无条件 cancel——
 * 安山漏斗永远不跟红石信号同步。</li>
 * </ul>
 */
@Mixin(AbstractFunnelBlock.class)
public abstract class AndesiteFunnelRedstoneMixin {

    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$ignoreRedstoneOnNeighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            BlockPos fromPos, boolean isMoving, CallbackInfo ci) {
        if (level.isClientSide() || !AndesiteFunnelRedstoneRules.isAndesiteFunnel(state)) {
            return;
        }
        InvManipulationBehaviour behaviour =
                BlockEntityBehaviour.get(level, pos, InvManipulationBehaviour.TYPE);
        if (behaviour != null) {
            behaviour.onNeighborChanged(fromPos);
        }
        if (AndesiteFunnelRedstoneRules.hasLegacyPowered(state)) {
            level.scheduleTick(pos, (Block) (Object) this, 1);
        }
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$resetLegacyPowered(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
            CallbackInfo ci) {
        if (!AndesiteFunnelRedstoneRules.isAndesiteFunnel(state)) {
            return;
        }
        if (AndesiteFunnelRedstoneRules.hasLegacyPowered(state)) {
            level.setBlock(pos, AndesiteFunnelRedstoneRules.withUnpowered(state), 2);
        }
        ci.cancel();
    }
}
