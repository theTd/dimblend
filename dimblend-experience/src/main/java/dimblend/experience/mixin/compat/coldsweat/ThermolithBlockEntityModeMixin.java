package dimblend.experience.mixin.compat.coldsweat;

import com.momosoftworks.coldsweat.common.blockentity.ThermolithBlockEntity;
import dimblend.experience.compat.coldsweat.ThermolithModeRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管恒温石的 tick（三种模式都接管）。
 *
 * <p>原版每 20 tick 按温度线性写 0–15 信号；冰/熔岩模式要的是"过阈值才满 15"的二值输出，
 * 而"邻近外源红石信号时停止输出"在三种模式下都必须成立，所以原版那段逻辑整段让位给
 * {@link ThermolithModeRules#tick}：NORMAL 复刻原版的线性插值（同样 20 tick 一次温度计算），
 * 抑制判定则每 tick 都做，红石输入变化当 tick 生效。</p>
 *
 * <p>HEAD 注入 + {@code cancel()}：客户端同样取消（客户端只读服务端同步来的
 * {@code POWERED}/{@code dimblend_suppressed}，不自己算）。</p>
 */
@Mixin(value = ThermolithBlockEntity.class, remap = false)
public abstract class ThermolithBlockEntityModeMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private void dimblend$applyModeSignal(Level level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (state.hasProperty(ThermolithModeRules.MODE)) {
            ThermolithModeRules.tick(level, pos, state, (ThermolithBlockEntity) (Object) this);
            ci.cancel();
        }
    }
}
