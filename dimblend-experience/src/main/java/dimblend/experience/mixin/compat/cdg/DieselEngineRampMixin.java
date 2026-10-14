package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineState;
import dimblend.experience.compat.cdg.CdgKineticOverload;
import dimblend.experience.compat.cdg.CdgLoadGrace;
import dimblend.experience.compat.cdg.CdgOverloadFuse;
import dimblend.experience.compat.cdg.CdgOverloadMath;
import dimblend.experience.compat.cdg.CdgOverloadProbe;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * B 板块 CDG 柴油机行为（CDG 1.3.15 源码基线）：
 * <ul>
 * <li>B1 加燃油启动：转速从 16rpm 起每 4 秒（80gt）+2rpm 阶梯爬升到额定</li>
 * <li>B2 燃尽走原版停机（不干预）</li>
 * <li>B3 到额定后在 80%~100% 间随机跳变，每 1~3 秒取一次新值（计时在 tick
 * 状态机内推进，getter 纯读）</li>
 * <li>B6 过载警告与引信（用户拍板，引信不可中断）：运转中过载确认累计首 tick 即播
 * {@code diesel_overstress.ogg} 1 次（2026-10-14 拍板提前，原在点引信当 tick）；
 * 确认前解除过载（累计清零）即掐掉警告音，点引信后播完不掐。连续 40 tick（2 秒）
 * 确认后点引信、出力归零，引信期间每 tick 播 large_smoke
 * （delta 0.2,0.2,0.2 / speed 0 / count 10）；2 秒（40 tick，2026-10-14 拍板，
 * 较原 6 秒提前 4 秒引爆）后播
 * {@code entity.generic.explode} 1 次 + 爆炸粒子（delta 1,1,1 / speed 0 /
 * count 100），再破坏自毁掉落（余油不返还、无真实爆炸伤害）。确认口径三关
 * （2026-10-13 修订，见 {@code CdgKineticOverload}）：缓存位真 + 已加载成员
 * 实时账过载 + 已加载应力超出全网额定容量——粘位/unloaded 幻影账/爬梯波动可覆盖
 * 的缺额都不累计且<b>不冻结</b>爬梯（引擎照常爬回额定或触发整网重建清账）；
 * 确认进度不跨存档携带。
 * 确认累计期间（真过载）爬梯/波动计时冻结。红石关停/燃尽/负载恢复都不取消引信。
 * 自毁破坏失败（极端情况）才回退闩锁逻辑</li>
 * </ul>
 * 目标：普通与组合式柴油机。巨型柴油机由 B5（HugeDieselEngineMixin）独立覆盖，
 * 本 mixin 不处理（cast 结构只接受 KineticBlockEntity）。
 * <p>燃油门控不在本 mixin：原收敛点是 {@code EngineFuelGateMixin}
 * （{@code IEngine#getFuelThrottle} RETURN 注入），2026-09-28 起停用
 * （commit a80c155，从 mixins.json/plugin 摘除，源文件保留）——闩锁/引信期间
 * 燃油当前照常消耗，属拍板停用状态，勿按本段旧描述理解。</p>
 * <p>1.3.15 口径对齐：两目标类的 {@code getGeneratedSpeed()} 均含
 * {@code * getThrottle()}（模拟信号调速，未开启时恒 1）。本 mixin 的额定饱和判定
 * 与运转判定同步乘 throttle——与 getter 同源，模拟调速 0 即视为停转复位。</p>
 * <p>配置读取时机：所有 handler 先行 ServerLevel 守卫（双端方法），仅服务端
 * 读取 SERVER 配置——避免专用服务器客户端未加载该配置即抛异常。</p>
 * <p>状态持久化：附件带 codec 序列化，闩锁/引信与点火计时跨区块卸载/存档重启保持；
 * 过载确认计数不序列化（读档从 0 重计）。</p>
 */
@Mixin({DieselEngineBlockEntity.class, ModularDieselEngineBlockEntity.class})
public abstract class DieselEngineRampMixin {

    @Unique
    private static final float dimblend$IGNITION_RPM = 16.0F;
    @Unique
    private static final float dimblend$RAMP_STEP_RPM = 2.0F;
    @Unique
    private static final int dimblend$RAMP_STEP_TICKS = 80;
    @Unique
    private static final float dimblend$FLUCT_MIN = 0.8F;
    @Unique
    private static final int dimblend$FLUCT_MIN_TICKS = 20;
    @Unique
    private static final int dimblend$FLUCT_MAX_TICKS = 60;

    /**
     * B1/B3：爬梯 + 额定波动。纯函数式：不推进任何计时（计时在 tick RETURN
     * 状态机内统一推进），同 tick 多次调用看到同一状态，无分裂值。
     */
    @Inject(method = "getGeneratedSpeed", at = @At("RETURN"), cancellable = true)
    public void dimblend$rampAndFluctuate(CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            return;
        }
        CdgEngineState state = self.getData(CdgAttachments.ENGINE_STATE);
        float vanilla = cir.getReturnValueF();
        if (Config.DIESEL_ENGINE_OVERLOAD.get() && state.overloadLatched) {
            cir.setReturnValue(0.0F);
            return;
        }
        if (vanilla == 0.0F) {
            return;
        }
        float sign = vanilla > 0.0F ? 1.0F : -1.0F;
        float rated = Math.abs(vanilla);
        float stepped = Math.min(
                dimblend$IGNITION_RPM + dimblend$RAMP_STEP_RPM * (state.rampTicks / dimblend$RAMP_STEP_TICKS),
                rated);
        if (stepped >= rated) {
            // B3：已到额定，按已抽取的波动系数缩放（系数抽取在 tick 状态机内）
            stepped = rated * state.fluctFactor;
        }
        cir.setReturnValue(sign * stepped);
    }

    /**
     * 状态机（每 tick 收尾，仅服务端）：引信进行中→只推进倒计时（不可中断，
     * 到时爆音+粒子+自毁）；燃尽→解除闩锁复位；重新加油（油量
     * 相对上次记录上升）→ 解除闩锁全新点火；运转中过载→累计首 tick 播警告音、
     * 满确认窗口<b>点引信</b>（出力归零，2 秒后自毁，失败回退闩锁；确认前解除过载
     * 即掐掉警告音，点引信后播完不掐）；运转中推进
     * 爬梯计时与波动抽取计时（getGeneratedSpeed 纯读系数）；<b>停转（红石关停/
     * 模拟调速归零）复位</b>——与燃尽/重新加油的"全新点火"语义一致（复核裁定，第 2 轮的
     * 续转语义已回退）。
     * 到额定窗口为确定性饱和判定：{@code 16 + 2×(rampTicks/80) >= rawRated}，
     * 与 getter 的阶梯公式同源（翻轉点=getter 开始应用波动系数的点），不依赖
     * 当前转速——无冻结/无爬梯段空转/无相位漂移。
     * 附件带序列化：闩锁/引信跨区块卸载/存档重启保持；跃迁标脏。
     */
    @Inject(method = "tick", at = @At("RETURN"))
    public void dimblend$updateIgnitionState(CallbackInfo ci) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return; // 客户端无 SERVER 配置，不读
        }
        if (!Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            return;
        }
        IEngine engine = (IEngine) (Object) this;
        CdgEngineState state = self.getData(CdgAttachments.ENGINE_STATE);
        if (!Config.DIESEL_ENGINE_OVERLOAD.get()) {
            // 关开关=未进不可挽回：累计中的警告音掐掉，再清闩锁/引信字段
            CdgOverloadFuse.clearConfirmation(level, state);
            if (state.clearOverload()) {
                self.setChanged();
                ((GeneratingKineticBlockEntity) self).updateGeneratedRotation();
            }
        }
        boolean wasLatched = state.overloadLatched;
        CdgOverloadProbe.touch(self);
        boolean loadGrace = CdgLoadGrace.active(level);

        boolean fuel = engine.validFS();
        int fuelAmount = engine.getTank().getFluidAmount();
        if (state.fuseActive) {
            // B6 引信优先且不可中断：燃油/红石/负载状态都不再干预，只推进倒计时；
            // 到时爆音+粒子+自毁（失败回退闩锁等重新加油）。组合式非 controller
            // 油箱恒空、本就点不着引信，只有持油的 controller 能进此分支。
            state.fuelPresent = fuel;
            state.lastFuelAmount = fuelAmount;
            if (loadGrace) {
                return; // 读档宽限：倒计时暂停，宽限过后接着走
            }
            if (CdgOverloadFuse.tickFuse(level, self.getBlockPos(), state,
                    () -> level.destroyBlock(self.getBlockPos(), true))) {
                state.overloadLatched = true;
                state.rampTicks = 0;
                state.fluctTicksLeft = 0;
            }
            // 引信倒计时每 tick 标脏：存档/崩溃窗口内丢失倒计时 vs 多一次 NBT 写，
            // 取前者（需求：卸载/重启不中断引信）
            self.setChanged();
            return;
        }
        if (state.overloadLatched) {
            // B4 重新加油触发启动：油量上升（相对上次记录）即解除闩锁全新点火
            if (fuel && fuelAmount > state.lastFuelAmount) {
                state.overloadLatched = false;
                state.rampTicks = 0;
                state.fluctTicksLeft = 0;
            }
        } else if (state.fuelPresent && !fuel) {
            // B2 燃尽：复位，下次加油为全新点火
            state.rampTicks = 0;
        }
        state.fuelPresent = fuel;
        state.lastFuelAmount = fuelAmount;
        if (!fuel) {
            CdgOverloadFuse.clearConfirmation(level, state);
            return;
        }

        boolean enabled = engine.enabled();
        boolean overloaded = CdgKineticOverload.refreshedOverstressed(self);
        if (Config.DIESEL_ENGINE_OVERLOAD.get() && enabled && overloaded) {
            // B6：确认口径全真（已加载实时账过载且超出全网额定容量）连续 40 tick
            // （2 秒，见 CdgOverloadMath/CdgKineticOverload）才点引信——累计期间
            // 爬梯/波动计时冻结（本 tick 直接 return，不断也不复位），燃油照常扣除
            // （门控仅闩锁/引信后生效）。累计首 tick 即播警告音（确认前解除过载
            // 由下方复位路径掐掉），确认后出力立即归零（闩锁口径），警告音播完
            // 不掐，2 秒后爆音+粒子+自毁掉落（余油不返还）。
            // refreshedOverstressed 判否的三种形态（粘位/毒账/爬梯可覆盖）都落回
            // 下方正常推进：overloadTicks 清零、爬梯照常——不冻结，让引擎爬回
            // 额定或靠转速变化触发整网重建清掉幻影份额。
            if (loadGrace) {
                // 读档宽限：网络仍在重建，不累计；同未确认口径冻结爬梯/波动
                CdgOverloadFuse.clearConfirmation(level, state);
                return;
            }
            state.overloadTicks = CdgOverloadMath.countOverloadTick(state.overloadTicks);
            if (state.overloadTicks == 1) {
                // 过载一开始（确认累计首 tick）即播警告音；确认前解除会被掐掉
                CdgOverloadFuse.startWarning(level, self.getBlockPos());
            }
            if (!CdgOverloadMath.isOverloadConfirmed(state.overloadTicks)) {
                return;
            }
            CdgOverloadProbe.fuseStart(self, self, "fuel=" + fuel + " fuelAmount=" + fuelAmount
                    + " enabled=" + enabled + " throttle=" + engine.getThrottle()
                    + " generatedSpeed=" + self.getGeneratedSpeed()
                    + " ramp=" + state.rampTicks + " fluct=" + state.fluctFactor
                    + " overloadTicks=" + state.overloadTicks + " wasLatched=" + wasLatched);
            CdgOverloadFuse.startFuse(level, self.getBlockPos(), state);
            if (wasLatched != state.overloadLatched) {
                self.setChanged();
            }
            return;
        }
        CdgOverloadFuse.clearConfirmation(level, state);
        if (!state.overloadLatched) {
            // throttle 口径（1.3.15）：getter 额定 = upgrade.getSpeed * throttle，
            // 饱和判定必须同源；throttle == 0（模拟调速关闭）视为停转复位
            float throttle = engine.getThrottle();
            boolean running = enabled && !overloaded && throttle > 0.0F;
            if (running) {
                // B3 到额定窗口：确定性饱和判定——与 getter 的阶梯公式是同一公式，
                // 翻转点=getter 开始应用波动系数的点；不依赖当前转速（无冻结/无空转/
                // 无相位漂移，复核员第 2 轮给出的修法）
                float rawRated = Math.abs(engine.getUpgrade().getSpeed(engine.getFuelSpeed(), engine) * throttle);
                float rampTarget = dimblend$IGNITION_RPM
                        + dimblend$RAMP_STEP_RPM * (state.rampTicks / dimblend$RAMP_STEP_TICKS);
                boolean reachedRated = rampTarget >= rawRated;
                state.rampTicks++;
                if (reachedRated) {
                    if (--state.fluctTicksLeft <= 0) {
                        state.fluctFactor = dimblend$FLUCT_MIN + level.random.nextFloat() * (1.0F - dimblend$FLUCT_MIN);
                        state.fluctTicksLeft = level.random.nextInt(dimblend$FLUCT_MIN_TICKS, dimblend$FLUCT_MAX_TICKS);
                    }
                } else {
                    state.fluctTicksLeft = 0;
                }
            } else {
                // 停转（红石关停）复位——与燃尽/重新加油的"全新点火"语义一致（复核裁定）
                state.rampTicks = 0;
                state.fluctTicksLeft = 0;
            }
        }
        // 低-B：闩锁跃迁标脏（NeoForge AttachmentType javadoc 要求，保证崩溃窗口内
        // 磁盘态不滞后）
        if (wasLatched != state.overloadLatched) {
            self.setChanged();
        }
    }
}
