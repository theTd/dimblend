package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dimblend.experience.compat.sable.SableOnboardMachineGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 水车 / 大水车落在 Sable 载具上即自愈破坏（2026-11-25）。
 *
 * <p>{@code WaterWheelBlockEntity} 自己不声明 {@code tick()}，注到它的父类
 * {@link SmartBlockEntity} 会波及全部方块实体，所以这里只在
 * {@code this instanceof WaterWheelBlockEntity} 时才真正判定——大水车
 * （{@code LargeWaterWheelBlockEntity}）继承自它，一处覆盖两种方块。</p>
 */
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class CreateWaterWheelSableGuardMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$destroyWaterWheelOnSable(CallbackInfo ci) {
        if ((Object) this instanceof WaterWheelBlockEntity
                && SableOnboardMachineGuard.destroyIfOnSubLevel((SmartBlockEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
