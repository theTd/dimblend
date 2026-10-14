package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.compat.simurail.TrainDeadmanBrake;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E7' 无人值守刹车锁存的转向架挂钩（服务端）。
 *
 * <p>{@code tick()V} RETURN：与 {@link PhysicsBogeyBrakeSoundMixin} 同一触发点——
 * 每游戏 tick 常跑、与红石采样同线程，未组装/物理未激活的车架也能收到
 * （锁存后列车已停，解除脉冲必须仍能采到）。逐架跟踪信号沿，通一次再断开即解除锁存。</p>
 *
 * <p>{@code getBrakeStrength()D} RETURN：锁存期间强制满刹（1.0）。施力方
 * {@code PhysicsBogeyAxle} 与 E3/E4 沿检测同读此方法，锁存/解除自动伴随
 * 施闸/松闸音效沿，正控/反控模式一致成立。可能在物理线程调用——锁存状态
 * 读写均为并发集合，无额外同步需求。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyDeadmanBrakeMixin {

    @Inject(method = "tick()V", at = @At("RETURN"), remap = false)
    private void dimblend$deadmanSignalTick(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (self.getLevel().isClientSide()) {
            return;
        }
        TrainDeadmanBrake.onBogeyServerTick(self);
    }

    @ModifyReturnValue(method = "getBrakeStrength()D", at = @At("RETURN"), remap = false)
    private double dimblend$deadmanFullBrake(double original) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (self.getLevel().isClientSide()) {
            return original;
        }
        if (TrainDeadmanBrake.isLatched(self.getLevel().dimension())) {
            return 1.0D;
        }
        return original;
    }
}
