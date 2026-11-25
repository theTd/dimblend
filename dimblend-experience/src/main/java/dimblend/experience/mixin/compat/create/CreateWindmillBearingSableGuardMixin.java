package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import dimblend.experience.compat.sable.SableOnboardMachineGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 风车轴承落在 Sable 载具上即自愈破坏（2026-11-25）。
 *
 * <p>风车轴承的产能依赖"周围有多少风能方块"，在载具上会被读成 plot 局部坐标而伪产能；
 * 放置侧已拦 {@code BlockItem#place}，这里兜住蓝图炮/结构生成等绕行路径。</p>
 */
@Mixin(value = WindmillBearingBlockEntity.class, remap = false)
public abstract class CreateWindmillBearingSableGuardMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$destroyOnSable(CallbackInfo ci) {
        if (SableOnboardMachineGuard.destroyIfOnSubLevel((WindmillBearingBlockEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
