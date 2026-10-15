package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.compat.simurail.TrainDeadmanBrake;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * E7' 无人值守刹车锁存强制驱动网络过载（服务端网络账本，2026-10-15 窗子拍板）：
 * 锁存要保持"掉下车=事故"——锁存期间让驱动列车的 Create 网络过载，触发动力源
 * （CDG 柴油机）自身过载惩罚（B6：2 秒确认警告 → 4 秒引信 → 爆炸）。
 *
 * <p>锁存转向架的 {@code calculateStressApplied} 读数替换为
 * {@link TrainDeadmanBrake#forcedImpactOf} 表中的强制值（进入锁存时按网络当前总容量
 * 反算：需求 = impact × |理论转速| > 容量 × 1.5 + 1；锁存瞬间无网络回退 65536）。
 * 立即生效/恢复由 {@code TrainDeadmanBrake.syncForcedImpacts} 在锁存变化时显式
 * {@code updateStressFor} 推账（Create 不会自动重算本方法）；本钩子是网络 split/merge
 * 重 {@code add()} 等重缓存路径的兜底（与 D8 同模式）——只读 ConcurrentHashMap，
 * 无副作用、无分配。表只服务端维护，客户端透传原值（护目镜显示不受影响）。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyDeadmanStressMixin {

    @ModifyReturnValue(method = "calculateStressApplied()F", at = @At("RETURN"), remap = false)
    private float dimblend$deadmanForcedStress(float original) {
        Float forced = TrainDeadmanBrake.forcedImpactOf((PhysicsBogeyBlockEntity) (Object) this);
        return forced != null ? forced : original;
    }
}
