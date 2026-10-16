package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * B11：双端屏蔽 CDG 的红石调速与红石停机，保留 validFS 的燃油检查和缓存更新。
 * getThrottle 恒 1；接口 enabled 仅由 validFS 决定；组合式额外成员检查由
 * ModularEngineRedstoneMixin 覆盖。配置关闭时全部透传原生，未加载时按默认开启。
 * 客户端也需覆盖：护目镜直接读取引擎的 generated speed / throttle。
 * 燃油倍率随之恒满档，不再由模拟信号降低。
 */
@Mixin(IEngine.class)
public interface EngineThrottleLockMixin {

    /** B11 实现：配置可读时尊重总开关（关=透传原始 throttle），否则恒 1。 */
    @Inject(method = "getThrottle()F", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$lockThrottleAtFull(CallbackInfoReturnable<Float> cir) {
        if (Config.isLoaded() && !Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            return;
        }
        cir.setReturnValue(1.0F);
    }

    /** Preserve the native fuel check while ignoring the redstone stop switch. */
    @Inject(method = "enabled()Z", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$ignoreRedstoneStop(CallbackInfoReturnable<Boolean> cir) {
        if (!Config.isLoaded() || Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            cir.setReturnValue(((IEngine) this).validFS());
        }
    }
}
