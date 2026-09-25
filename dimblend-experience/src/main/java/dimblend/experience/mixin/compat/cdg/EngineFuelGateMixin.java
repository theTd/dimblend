package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import dimblend.experience.Config;
import dimblend.experience.compat.cdg.CdgAttachments;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * B4 燃油门控（CDG 1.3.15 口径）：过载闩锁期间燃油消耗系数归零——不出力也不耗油。
 *
 * <p>1.3.15 起三类柴油机的 tick 燃油扣除统一走
 * {@code fuelDebt += burn * getFuelThrottle()}（普通/组合式/巨型机 tick 内各 1 处），
 * 而组合式 tick 已不再直调 {@code enabled()}（旧版 DieselEngineRampMixin 对
 * {@code enabled()Z} 的 ModifyExpressionValue 在此无命中点，会触发
 * defaultRequire=1 报错；且即使命中也拦不住组合式的无条件扣油）。
 * 因此燃油门统一收敛到接口默认方法 {@link IEngine#getFuelThrottle} 的 RETURN 注入：
 * 一个注入点覆盖全部三类实现（含巨型机——其闩锁回退路径此前扣油拦不住，本次一并收敛），
 * 且对上游 tick 内部重排免疫。</p>
 *
 * <p>闩锁期间 fuelDebt 不再累积（相对"在 drain 调用点吞掉"的优势）：解除闩锁重新点火时
 * 不会因残留 fuelDebt 瞬间扣掉新加的油。全部 handler 先行 ServerLevel 守卫，仅服务端
 * 读取 SERVER 配置。</p>
 */
@Mixin(IEngine.class)
public interface EngineFuelGateMixin {

    /**
     * B4 燃油门控实现。注意本 mixin 必须是 interface 形态：
     * Sponge Mixin 要求目标为接口时 mixin 自身也为接口（类形态会在 apply 期报
     * target type mismatch），default 方法体随接口层级合并，全部 IEngine 实现
     * 类自动获得该注入。
     */
    @Inject(method = "getFuelThrottle", at = @At("RETURN"), cancellable = true)
    default void dimblend$noBurnWhileLatched(CallbackInfoReturnable<Float> cir) {
        IEngine engine = (IEngine) (Object) this;
        if (!(engine.self().getLevel() instanceof ServerLevel)) {
            return; // 客户端无 SERVER 配置，不读
        }
        if (!Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            return;
        }
        if (engine.self().getData(CdgAttachments.ENGINE_STATE).overloadLatched) {
            cir.setReturnValue(0.0F);
        }
    }
}
