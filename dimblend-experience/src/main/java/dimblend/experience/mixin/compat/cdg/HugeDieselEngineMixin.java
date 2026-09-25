package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.PoweredEngineShaftBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineState;
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
 * <p>过载信号源 = {@code shaft.isOverStressed()}（轴为 GeneratingKineticBlockEntity）；
 * 爆机只炸引擎本体（用户拍板）：先调 {@code shaft.removeGenerator} 摘除轴侧登记
 * （否则轴残留末速空转），再 {@code destroyBlock(pos, true)} 按战利品表掉落，
 * 余油不返还。燃尽走原版停机（remainingTicks/enabled 原逻辑，不干预）。</p>
 *
 * <p>状态复用 {@link CdgEngineState} 附件（rampTicks/fluct 系，持久化）；
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
     * 非服务端原样透传（轴转速客户端由网络同步）；闩锁（爆机失败回退）期间给 0。
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
        if (state.overloadLatched) {
            return 0.0F;
        }
        float stepped = Math.min(
                dimblend$IGNITION_RPM + dimblend$RAMP_STEP_RPM * (state.rampTicks / dimblend$RAMP_STEP_TICKS),
                rated);
        if (stepped >= rated) {
            stepped = rated * state.fluctFactor;
        }
        return stepped;
    }

    /**
     * B5 计时 + 过载爆机（每 tick 收尾，仅服务端）：爬梯计时推进，到额定窗口
     * （与替换公式同源的饱和判定）抽取波动系数；轴过载 → 摘登记 + 炸本体。
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
        PoweredEngineShaftBlockEntity shaft = self.getShaft();
        if (shaft == null) {
            return;
        }
        CdgEngineState state = self.getData(CdgAttachments.ENGINE_STATE);
        int fuelAmount = self.getTank().getFluidAmount();
        if (state.overloadLatched) {
            // 爆机失败回退：闩锁语义与普通机一致，重新加油（有效燃油且油量上升）解除、全新点火
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
            state.rampTicks = 0;
            state.fluctTicksLeft = 0;
            return;
        }
        if (shaft.isOverStressed()) {
            // B5 过载损坏（只炸本体）：先摘轴侧登记防残留末速空转，再破坏掉落
            BlockPos pos = self.getBlockPos();
            shaft.removeGenerator(pos);
            if (level.destroyBlock(pos, true)) {
                return;
            }
            state.overloadLatched = true;
            state.rampTicks = 0;
            state.fluctTicksLeft = 0;
            return;
        }
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
    }
}
