package dimblend.experience.mixin.compat.fluid;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dimblend.experience.compat.sable.LavaFlowGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * 车顶保留岩浆的止流闸门（2026-11-03 用户实机缺陷）：原版 {@code FlowingFluid#spread} 先试
 * DOWN，{@code canHoldFluid} 对 {@code structure_void} 恒 false，于是必然走
 * {@code spreadToSides}——熔岩停在车顶拟合空位上方时仍向四周铺开。本 mixin 只拦
 * {@code spread} 里的两个水平分支（{@code spreadToSides} 调用点），向下落保持原版语义
 * （DoD："闸门不能挡掉向下落"）。
 *
 * <p>判据只有一条：该格是否登记在 {@link LavaFlowGuard}（{@code VoidFitApplier} 判定
 * "保留岩浆"时登记、载具离开/空位拆除时释放）。登记格只会是岩浆，这里再按
 * {@link FluidTags#LAVA} 复核一次，避免同格后来被换成水也一起冻住。</p>
 *
 * <p>注入钉死 {@code require = 2, allow = 2}：1.21.1 的 {@code spread} 恰好两个
 * {@code spreadToSides} 调用点（下落成功后的补铺、下落失败后的横铺）；原版改签名或增删
 * 调用点即装包期报错，而不是静默失效。</p>
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidFlowGuardMixin {

    @Inject(
            method = "spread",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FlowingFluid;spreadToSides"
                            + "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/material/FluidState;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)V"),
            cancellable = true,
            require = 2,
            allow = 2)
    private void dimblend$blockSideSpread(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (state.is(FluidTags.LAVA) && LavaFlowGuard.contains(level.dimension(), pos.asLong())) {
            ci.cancel();
        }
    }
}
