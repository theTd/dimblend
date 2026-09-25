package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlock;
import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E5 取消自动车钩的红石信号解锁（用户拍板口径：仅车钩，贯通框不动）。
 *
 * <p>{@link AutomaticCouplerBlock#neighborChanged} 在收到邻居红石信号时置
 * POWERED 并调 {@code tryDisconnectGangway}；本 mixin 在其 HEAD 取消整个方法，
 * 红石信号不再产生任何效果（不断开、也不改 POWERED/TRIGGERED 状态）。
 * 双端无条件取消（客户端预测性调用也会改本地状态，不取消会漂移至下次同步；
 * SERVER 配置只在服务端分支读，专用客户端不触配置——复核在案）；
 * 仅在 Simurail 在场时应用。</p>
 */
@Mixin(AutomaticCouplerBlock.class)
public abstract class AutomaticCouplerRedstoneMixin {

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void dimblend$noRedstoneUnlock(BlockState state, Level level, BlockPos pos,
            Block neighborBlock, BlockPos neighborPos, boolean movedByPiston, CallbackInfo ci) {
        if (!level.isClientSide() && !Config.COUPLER_REDSTONE.get()) {
            return;
        }
        ci.cancel();
    }
}
