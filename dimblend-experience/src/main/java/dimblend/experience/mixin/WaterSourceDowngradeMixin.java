package dimblend.experience.mixin;

import dimblend.experience.Config;
import dimblend.experience.exploration.IsolatedWaterRules;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * G3 孤立源水降级（原版 {@code Level#setBlock(4 参)} 目标，无条件应用）：
 * 实现体在 {@code Level}（{@code ServerLevel} 未重写该方法，直接 Mixin
 * {@code ServerLevel} 会因找不到目标方法导致启动期
 * {@code InvalidInjectionException} 崩溃），handler 首行以
 * {@code instanceof ServerLevel} 收窄，只处理服务端 rotating 内写入。
 *
 * <p>新态是纯水源且水平四邻源水 <2 时，取消原调用、改以 water7 重入一次
 * {@code setBlock}（重入由 {@code dimblend$replacing} 守卫放行，避免自递归）。
 * 热路径排序：重入守卫→服务端判定→纯水源判定（廉价，非水调用到此即止）→
 * 开关→维度→四邻计数（4 次方块查询，仅源水写入才走到）。</p>
 */
@Mixin(Level.class)
public abstract class WaterSourceDowngradeMixin {

    @Unique
    private static final ThreadLocal<Boolean> dimblend$replacing =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void dimblend$downgradeIsolatedWater(
            BlockPos pos, BlockState newState, int flags, int recursion,
            CallbackInfoReturnable<Boolean> ci) {
        if (ci.isCancelled() || dimblend$replacing.get()) {
            return;
        }
        if (!((Object) this instanceof ServerLevel self)) {
            return;
        }
        if (!IsolatedWaterRules.isWaterSource(newState)) {
            return;
        }
        if (!Config.ISOLATED_WATER_DOWNGRADE.get()) {
            return;
        }
        if (!RotatingDimension.is(self)) {
            return;
        }
        BlockState replacement = IsolatedWaterRules.downgradeIfIsolated(self, pos, newState);
        if (replacement == null) {
            return;
        }
        dimblend$replacing.set(Boolean.TRUE);
        try {
            ci.setReturnValue(self.setBlock(pos, replacement, flags, recursion));
        } finally {
            dimblend$replacing.set(Boolean.FALSE);
        }
    }
}
