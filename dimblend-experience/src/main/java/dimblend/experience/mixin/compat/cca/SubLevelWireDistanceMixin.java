package dimblend.experience.mixin.compat.cca;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.compat.cca.SableCoupledWireRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable 载具之间禁止直接拉电线（2026-11-25 用户口径）。
 *
 * <p>{@code sable_createaddition_compat} 的 {@code SubLevelWireTransforms#wireDistanceSquared}
 * 统一负责"子层级内的端点投影 + 距离计算"，Create: Addition 的接线判定只看这个距离
 * 与线轴长度。跨载具（以及载具↔真实世界）连线这里返回 {@code Double.MAX_VALUE}，
 * 上游自然判定为"过长"并返回 {@code WireConnectResult.LONG}。
 * 同载具内部与真实世界之间的正常布线不受影响（规则本体在
 * {@link SableCoupledWireRules}）。</p>
 *
 * <p>该类来自独立兼容 jar（{@code sable_createaddition_compat}），不在编译期依赖里，
 * 因此用 {@code targets} 字符串 + {@link Pseudo}，并由 mixin 插件按类存在性门控。</p>
 */
@Pseudo
@Mixin(targets = "dev.codex.sablecreateadditioncompat.SubLevelWireTransforms", remap = false)
public abstract class SubLevelWireDistanceMixin {

    @ModifyReturnValue(method = "wireDistanceSquared", at = @At("RETURN"), require = 1, allow = 1)
    private static double dimblend$banCrossSubLevelWires(double original, Level level,
            BlockPos pos1, Vec3 offset1, BlockPos pos2, Vec3 offset2) {
        if (SableCoupledWireRules.allowsWire(level, pos1, pos2)) {
            return original;
        }
        return Double.MAX_VALUE;
    }
}
