package dimblend.experience.mixin.compat.coldsweat;

import com.momosoftworks.coldsweat.common.blockentity.ThermolithBlockEntity;
import dimblend.experience.compat.coldsweat.ThermolithMode;
import dimblend.experience.compat.coldsweat.ThermolithModeRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 非 {@code NORMAL} 模式下接管恒温石的 tick：原版每 20 tick 按温度线性写 0–15 信号，
 * 冰/熔岩模式要的是"过阈值才满 15"的二值输出，两者不能共存。
 *
 * <p>HEAD 注入 + {@code cancel()}：模式非 NORMAL 时原版那套线性逻辑整段跳过（含它自己的
 * {@code POWERED} 状态翻转），信号与亮度全由 {@link ThermolithModeRules#evaluateAndApply}
 * 按模式决定。客户端侧同样取消——服务端会同步 {@code POWERED}，客户端无需自己算。</p>
 */
@Mixin(value = ThermolithBlockEntity.class, remap = false)
public abstract class ThermolithBlockEntityModeMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private void dimblend$applyModeSignal(Level level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!state.hasProperty(ThermolithModeRules.MODE)
                || state.getValue(ThermolithModeRules.MODE) == ThermolithMode.NORMAL) {
            return;
        }

        // 与原版同节拍（20 tick）重算；点击切换的那一刻由事件侧立即补算一次。
        if (!level.isClientSide && level.getGameTime() % 20L == 0L) {
            ThermolithModeRules.evaluateAndApply(level, pos, state, (ThermolithBlockEntity) (Object) this);
        }

        ci.cancel();
    }
}
