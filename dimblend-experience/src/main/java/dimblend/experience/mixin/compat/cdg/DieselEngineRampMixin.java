package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineState;
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
 * <li>B4 已改为过载爆机（用户拍板）：运转中过载 → 方块直接破坏、按战利品表
 * 掉成物品；油箱余油不返还。破坏失败（极端情况）才回退闩锁逻辑</li>
 * </ul>
 * 目标：普通与组合式柴油机。巨型柴油机由 B5（HugeDieselEngineMixin）独立覆盖，
 * 本 mixin 不处理（cast 结构只接受 KineticBlockEntity）。
 * <p>B4 燃油门控不在这里：1.3.15 起燃油扣除统一走
 * {@code fuelDebt += burn * getFuelThrottle()}，且组合式 tick 内已无
 * {@code enabled()} 直调点——门控收敛到 {@link EngineFuelGateMixin}
 *（{@code IEngine#getFuelThrottle} RETURN 注入，一处覆盖三类机型）。</p>
 * <p>1.3.15 口径对齐：两目标类的 {@code getGeneratedSpeed()} 均含
 * {@code * getThrottle()}（模拟信号调速，未开启时恒 1）。本 mixin 的额定饱和判定
 * 与运转判定同步乘 throttle——与 getter 同源，模拟调速 0 即视为停转复位。</p>
 * <p>配置读取时机：所有 handler 先行 ServerLevel 守卫（双端方法），仅服务端
 * 读取 SERVER 配置——避免专用服务器客户端未加载该配置即抛异常。</p>
 * <p>状态持久化：附件带 codec 序列化，闩锁与点火计时跨区块卸载/存档重启保持。</p>
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
        if (state.overloadLatched) {
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
     * 状态机（每 tick 收尾，仅服务端）：燃尽→解除闩锁复位；重新加油（油量
     * 相对上次记录上升）→ 解除闩锁全新点火；运转中过载→<b>爆机</b>（破坏掉落，
     * 失败回退闩锁）；运转中推进
     * 爬梯计时与波动抽取计时（getGeneratedSpeed 纯读系数）；<b>停转（红石关停/
     * 模拟调速归零）复位</b>——与燃尽/重新加油的"全新点火"语义一致（复核裁定，第 2 轮的
     * 续转语义已回退）。
     * 到额定窗口为确定性饱和判定：{@code 16 + 2×(rampTicks/80) >= rawRated}，
     * 与 getter 的阶梯公式同源（翻轉点=getter 开始应用波动系数的点），不依赖
     * 当前转速——无冻结/无爬梯段空转/无相位漂移。
     * 附件带序列化：闩锁跨区块卸载/存档重启保持；跃迁标脏。
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
        boolean wasLatched = state.overloadLatched;

        boolean fuel = engine.validFS();
        int fuelAmount = engine.getTank().getFluidAmount();
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
            return;
        }

        boolean enabled = engine.enabled();
        boolean overloaded = self.isOverStressed();
        if (enabled && overloaded) {
            // B4：运转中过载 → 爆机：方块破坏、按战利品表掉成物品（油箱余油不返还）。
            // 成功后 BE 即将卸载，后续附件写操作无害；失败才回退闩锁。
            if (level.destroyBlock(self.getBlockPos(), true)) {
                state.rampTicks = 0;
                state.fluctTicksLeft = 0;
                return;
            }
            state.overloadLatched = true;
            state.rampTicks = 0;
            state.fluctTicksLeft = 0;
        }
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