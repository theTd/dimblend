package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.experience.compat.cdg.CdgEngineDamage;

/**
 * B6'（2026-11-26 口径）活塞损坏期的"不出力 + 不烧油 + 不发声"总闸。
 *
 * <p><b>历史</b>：本 mixin 原本是 B4 的"过载闩锁期间不耗油"门控，2026-09-28 随闩锁口径调整
 * 从 {@code dimblend_experience.mixins.json} 摘除（<b>至今仍未注册</b>，注册与否见
 * {@code .work-notes/slice-2.md}）。本次把语义<b>收窄</b>为"只认 pistonDamaged"，
 * 不复活旧 {@code overloadLatched} 门控（旧语义会把"引信倒计时期间不耗油"一并带回，
 * 用户没有要求，且会让引信期的油耗表现和过去不一致）。</p>
 *
 * <p><b>为什么两个注入点都要</b>（CDG 1.3.15 源码逐条核对）：
 * <ol>
 * <li>{@code enabled()Z} —— 普通机 {@code tick} 的扣油块在 {@code if (enabled() && !isOverStressed())} 内；
 * 三型的 {@code tickClient} 一律 {@code if (enabled() && getThrottle() > 0 && !isOverStressed())}
 * 才 keepAlive 运转音，否则 {@code fadeOut()}；{@code getGeneratedSpeed} 也走
 * {@code enabled() ? ... : 0}。→ 归零即"不出力 + 运转音淡出"，无需再单独拦客户端音效。</li>
 * <li>{@code getFuelThrottle()F} —— <b>组合式</b>（{@code ModularDieselEngineBlockEntity}）controller 的扣油块是
 * {@code if (isOverStressed()) return; fuelDebt += (length * cachedBurnRate) * getFuelThrottle();}
 * ——<b>它不检查 enabled()</b>。只堵 {@code enabled()} 的话组合式照样烧油。
 * 一个 {@code getFuelThrottle} 注入同时覆盖普通机（{@code fuelDebt += cachedBurnRate * getFuelThrottle()}）、
 * 组合式、巨型机（{@code if (enabled() && getThrottle() > 0) { ... getFuelThrottle() ... }}）。</li>
 * </ol></p>
 *
 * <p><b>必须保持 interface 形态</b>：Sponge Mixin 要求目标为接口时 mixin 自身也是接口
 * （类形态 apply 期报 target type mismatch）；default 方法体随接口层级合并，
 * 全部 {@link IEngine} 实现类自动获得注入。handler 一律 private 语义（接口 default 本身不污染目标类，
 * 见 AGENTS.md mixin 纪律）。</p>
 *
 * <p>副作用说明：巨型机 {@code enabled()==false} 时会走 {@code shaft.removeGenerator(pos)}，
 * 等于"损坏即摘轴登记"+ 不再有空转末速，这正是想要的结果，不需要额外处理。</p>
 */
@Mixin(value = IEngine.class, remap = false)
public interface EngineFuelGateMixin {

    /**
     * 损坏期间燃油消耗系数归零。
     * 用 HEAD 而不是 RETURN：先于目标方法体内的 {@code getThrottle()} 求值返回，
     * 避免无谓计算；对上游 tick 内部重排免疫。
     */
    @Inject(method = "getFuelThrottle", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    default void dimblend$noBurnWhileDamaged(CallbackInfoReturnable<Float> cir) {
        if (CdgEngineDamage.isDamaged((IEngine) (Object) this)) {
            cir.setReturnValue(0.0F);
        }
    }

    /**
     * 损坏期间视为"未启用"：出力 0、运转音停、巨型机自动摘轴。
     * 客户端也会走到这里（{@code pistonDamaged} 随 BE NBT 同步），这是"静音"的真正开关。
     */
    @Inject(method = "enabled", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    default void dimblend$disabledWhileDamaged(CallbackInfoReturnable<Boolean> cir) {
        if (CdgEngineDamage.isDamaged((IEngine) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
