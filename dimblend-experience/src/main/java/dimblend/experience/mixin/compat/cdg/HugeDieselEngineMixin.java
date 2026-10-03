package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.PoweredEngineShaftBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineState;
import dimblend.experience.compat.cdg.CdgKineticOverload;
import dimblend.experience.compat.cdg.CdgLoadGrace;
import dimblend.experience.compat.cdg.CdgOverloadFuse;
import dimblend.experience.compat.cdg.CdgOverloadMath;
import dimblend.experience.compat.cdg.CdgOverloadProbe;
import dimblend.experience.compat.cdg.CdgRatedCapacityMath;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * B5 大型柴油引擎：热机爬梯 + 额定波动 + 过载损坏（与普通/组合式同口径）。
 *
 * <p>巨型机为 {@code SmartBlockEntity}（非 Kinetic），经
 * {@link PoweredEngineShaftBlockEntity#update} 出力：tick 内唯一一次
 * {@code shaft.update(pos, dir, capacity, cachedFuelSpeed * throttle)} 调用
 * （CDG 1.3.15 源码）把转速写入轴的多引擎表并取最大输出。
 * 调速点 = 该调用的 speed 参数（index 3，{@link #dimblend$rampSpeed} 替换）；
 * 计时与波动抽取在 tick RETURN 推进（getter/参数替换保持纯函数）。
 * 饱和判定与该调用实参同源（{@code |getFuelSpeed * getThrottle|}，注意 1.3.15
 * 此处未乘 upgrade 倍率——与上游保持一致，不另做修正）。</p>
 *
 * <p>B6 过载引信（用户拍板，不可中断）：轴缓存 {@code overStressed} 为真时先按实时
 * 容量/应力复核（见 {@code CdgKineticOverload}；加载期 {@code addSilently} 不 sync，
 * 缓存位会粘住），实时过载连续满 40 tick（2 秒）才确认。确认后先摘轴侧登记
 * （否则轴残留末速空转），再点引信——警告音 1 次、出力归零，
 * 引信期间每 tick 播 large_smoke（delta 0.2,0.2,0.2 / speed 0 / count 10）；
 * 实时不过载不累计（确认进度不跨存档携带）。6 秒后爆音 1 次 + 爆炸粒子
 * （delta 1,1,1 / speed 0 / count 100），再 {@code destroyBlock(pos, true)}
 * 破坏本体掉落（余油不返还、无真实爆炸伤害）。红石关停/燃尽/负载恢复都不取消引信。
 * 自毁破坏失败（极端情况）才回退闩锁等重新加油。燃尽走原版停机（不干预）。</p>
 *
 * <p>B7 应力容量恒按额定（用户拍板 2026-09-30）：同一 {@code shaft.update} 调用的
 * 每转容量实参（index 2，{@link #dimblend$ratedCapacity} 替换）按 额定/轴转速 放大，
 * 爬梯/波动只改转速，本机对轴的贡献不低于额定总 SU。</p>
 *
 * <p>状态复用 {@link CdgEngineState} 附件（rampTicks/fluct 系 + 引信，持久化）；
 * 全部 handler 先行 ServerLevel 守卫，仅服务端读 SERVER 配置。</p>
 */
@Mixin(HugeDieselEngineBlockEntity.class)
public abstract class HugeDieselEngineMixin {

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
     * B5 调速：替换传给轴的额定转速为爬梯/波动值。tick 双端执行，
     * 非服务端原样透传（轴转速客户端由网络同步）；闩锁（引信进行中/爆机失败回退）期间给 0。
     */
    @ModifyArg(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/jesz/createdieselgenerators/content/diesel_engine/huge/PoweredEngineShaftBlockEntity;update(Lnet/minecraft/core/BlockPos;IFF)V"),
            index = 3)
    private float dimblend$rampSpeed(float rated) {
        HugeDieselEngineBlockEntity self = (HugeDieselEngineBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return rated;
        }
        if (!Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            return rated;
        }
        CdgEngineState state = self.getData(CdgAttachments.ENGINE_STATE);
        if (Config.DIESEL_ENGINE_OVERLOAD.get() && state.overloadLatched) {
            return 0.0F;
        }
        return dimblend$steppedSpeed(state, rated);
    }

    /**
     * B7 应力容量恒按额定：替换传给轴的每转容量（index 2）。轴总容量 = Σ 每转容量 × 轴转速
     * （引擎表最快一台），本机按 额定/轴转速 放大，使本机贡献 = 每转容量 × max(额定, 轴转速)
     * （见 CdgRatedCapacityMath）。轴转速 = 本机本 tick 的爬梯/波动值与表内其他引擎已登记转速
     * 的最大值；别台转速变化后，本机下一 tick 重算重登记（轴 update 对值未变直接 return）。
     * 额定取与 speed 实参原值同源的 {@code cachedFuelSpeed * throttle}，不依赖两个 ModifyArg 的
     * 施加顺序。闩锁（引信中/爆机失败回退）原样透传。
     */
    @ModifyArg(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/jesz/createdieselgenerators/content/diesel_engine/huge/PoweredEngineShaftBlockEntity;update(Lnet/minecraft/core/BlockPos;IFF)V"),
            index = 2)
    private float dimblend$ratedCapacity(float capacityPerRpm) {
        HugeDieselEngineBlockEntity self = (HugeDieselEngineBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return capacityPerRpm;
        }
        if (!Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            return capacityPerRpm;
        }
        CdgEngineState state = self.getData(CdgAttachments.ENGINE_STATE);
        if (Config.DIESEL_ENGINE_OVERLOAD.get() && state.overloadLatched) {
            return capacityPerRpm;
        }
        float rated = self.getCachedFuelSpeed() * self.getThrottle();
        float shaftSpeed = Math.abs(dimblend$steppedSpeed(state, rated));
        PoweredEngineShaftBlockEntity shaft = self.getShaft();
        if (shaft != null) {
            BlockPos pos = self.getBlockPos();
            for (Pair<BlockPos, Couple<Float>> engine : shaft.engines) {
                if (!engine.getFirst().equals(pos)) {
                    shaftSpeed = Math.max(shaftSpeed, Math.abs(engine.getSecond().getSecond()));
                }
            }
        }
        return CdgRatedCapacityMath.shaftCapacityPerRpm(capacityPerRpm, rated, shaftSpeed);
    }

    /** B5 爬梯/波动转速（调速点与容量放大共用同一公式；闩锁由调用方先判）。 */
    @Unique
    private static float dimblend$steppedSpeed(CdgEngineState state, float rated) {
        float stepped = Math.min(
                dimblend$IGNITION_RPM + dimblend$RAMP_STEP_RPM * (state.rampTicks / dimblend$RAMP_STEP_TICKS),
                rated);
        if (stepped >= rated) {
            stepped = rated * state.fluctFactor;
        }
        return stepped;
    }

    /**
     * B5 计时 + B6 过载引信（每 tick 收尾，仅服务端）：引信进行中→只推进倒计时
     * （不可中断，到时摘登记+爆音+粒子+自毁）；闩锁回退→重新加油解除；
     * 停转（无油/红石关停/燃尽/模拟调速归零）→复位爬梯计时（引信除外）；
     * 轴过载 → 摘登记 + 点引信（警告音 1 次）。爬梯/波动计时与普通机同口径。
     */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void dimblend$hugeIgnitionAndOverload(CallbackInfo ci) {
        HugeDieselEngineBlockEntity self = (HugeDieselEngineBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            return;
        }
        CdgEngineState state = self.getData(CdgAttachments.ENGINE_STATE);
        if (!Config.DIESEL_ENGINE_OVERLOAD.get() && state.clearOverload()) {
            self.setChanged();
        }
        boolean wasLatched = state.overloadLatched;
        CdgOverloadProbe.touch(self);
        boolean loadGrace = CdgLoadGrace.active(level);
        if (state.fuseActive) {
            if (loadGrace) {
                return; // 读档宽限：倒计时暂停，宽限过后接着走
            }
            // B6 引信优先且不可中断：只推进倒计时；到时爆音+粒子+自毁
            // （自毁前重摘一次登记——倒计时内轴登记可能被别的引擎 tick 覆盖写回；
            // 轴已不在时跳过摘登记直接自毁，不停摆）。
            // BE 存活守卫：本体已被手拆时 level.getBlockEntity(pos) 不再是 self，
            // 不再自毁，只清引信（警告音/爆炸音已播完，不追回）。
            BlockPos pos = self.getBlockPos();
            if (CdgOverloadFuse.tickFuse(level, pos, state, () -> {
                if (level.getBlockEntity(pos) != (Object) self) {
                    return true;
                }
                PoweredEngineShaftBlockEntity s = self.getShaft();
                if (s != null) {
                    s.removeGenerator(pos);
                }
                return level.destroyBlock(pos, true);
            })) {
                state.overloadLatched = true;
                state.rampTicks = 0;
                state.fluctTicksLeft = 0;
            }
            // 引信倒计时每 tick 标脏（理由见 DieselEngineRampMixin）
            self.setChanged();
            return;
        }
        PoweredEngineShaftBlockEntity shaft = self.getShaft();
        if (shaft == null) {
            state.overloadTicks = 0;
            return;
        }
        int fuelAmount = self.getTank().getFluidAmount();
        if (state.overloadLatched) {
            // 自毁失败回退：闩锁语义与普通机一致，重新加油（有效燃油且油量上升）解除、全新点火
            if (self.validFS() && fuelAmount > state.lastFuelAmount) {
                state.overloadLatched = false;
                state.rampTicks = 0;
                state.fluctTicksLeft = 0;
            }
        }
        state.lastFuelAmount = fuelAmount;
        if (!self.enabled() || self.getThrottle() == 0.0F) {
            // 未运行（无油/红石关停/燃尽/模拟调速归零）：复位计时，与普通机"停转复位"一致；
            // 且轴过载此时与其他引擎有关，不得炸本机
            state.overloadTicks = 0;
            state.rampTicks = 0;
            state.fluctTicksLeft = 0;
            return;
        }
        if (Config.DIESEL_ENGINE_OVERLOAD.get() && CdgKineticOverload.refreshedOverstressed(shaft)) {
            // B6 过载损坏（只炸本体）：实时容量盖不住应力时连续 40 tick（2 秒，见
            // CdgOverloadMath）确认才点引信。粘住的缓存位在复核时按实时值刷掉，
            // 不累计。确认前爬梯/波动计时冻结（直接 return，不断也不复位），
            // 燃油照常扣除；确认后先摘轴侧登记防残留末速空转，再点引信
            if (loadGrace) {
                // 读档宽限：网络仍在重建，不累计；同未确认口径冻结爬梯/波动
                state.overloadTicks = 0;
                return;
            }
            state.overloadTicks = CdgOverloadMath.countOverloadTick(state.overloadTicks);
            if (!CdgOverloadMath.isOverloadConfirmed(state.overloadTicks)) {
                return;
            }
            BlockPos pos = self.getBlockPos();
            // 探针先于摘登记：摘轴侧登记会改轴的账本，先转储才能看到误判当时的读数
            CdgOverloadProbe.fuseStart(self, shaft, "fuel=" + self.validFS() + " fuelAmount=" + fuelAmount
                    + " enabled=" + self.enabled() + " throttle=" + self.getThrottle()
                    + " fuelSpeed=" + self.getFuelSpeed() + " cachedFuelSpeed=" + self.getCachedFuelSpeed()
                    + " ramp=" + state.rampTicks + " fluct=" + state.fluctFactor
                    + " overloadTicks=" + state.overloadTicks + " wasLatched=" + wasLatched
                    + " shaftEngines=" + shaft.engines.size());
            shaft.removeGenerator(pos);
            CdgOverloadFuse.startFuse(level, pos, state);
            if (wasLatched != state.overloadLatched) {
                self.setChanged();
            }
            return;
        }
        state.overloadTicks = 0;
        // 饱和判定与传轴实参同源（见类 javadoc）：|getFuelSpeed * getThrottle|
        float rated = Math.abs(self.getFuelSpeed() * self.getThrottle());
        float rampTarget = dimblend$IGNITION_RPM
                + dimblend$RAMP_STEP_RPM * (state.rampTicks / dimblend$RAMP_STEP_TICKS);
        state.rampTicks++;
        if (rampTarget >= rated) {
            if (--state.fluctTicksLeft <= 0) {
                state.fluctFactor = dimblend$FLUCT_MIN + level.random.nextFloat() * (1.0F - dimblend$FLUCT_MIN);
                state.fluctTicksLeft = level.random.nextInt(dimblend$FLUCT_MIN_TICKS, dimblend$FLUCT_MAX_TICKS);
            }
        } else {
            state.fluctTicksLeft = 0;
        }
        // 不逐 tick 标脏（与普通机一致：只有闩锁跃迁才标；重载后重爬无代价）
        // 引信 tick 内的倒计时不标脏：崩溃窗口内至多丢 1 tick 倒计时，可接受
    }
}
