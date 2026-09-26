package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.PoweredEngineShaftBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineState;
import dimblend.experience.compat.cdg.CdgOverloadFuse;
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
 * <p>B6 过载引信（用户拍板，不可中断）：轴过载当 tick 先摘轴侧登记（否则轴残留
 * 末速空转），再点引信——警告音 1 次、出力归零；6 秒后爆音 1 次 + 爆炸粒子
 * （delta 1,1,1 / speed 0 / count 100），再 {@code destroyBlock(pos, true)}
 * 破坏本体掉落（余油不返还、无真实爆炸伤害）。红石关停/燃尽/负载恢复都不取消引信。
 * 自毁破坏失败（极端情况）才回退闩锁等重新加油。燃尽走原版停机（不干预）。</p>
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
        boolean wasLatched = state.overloadLatched;
        if (state.fuseActive) {
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
            state.rampTicks = 0;
            state.fluctTicksLeft = 0;
            return;
        }
        if (shaft.isOverStressed()) {
            // B6 过载损坏（只炸本体）：先摘轴侧登记防残留末速空转，再点引信
            BlockPos pos = self.getBlockPos();
            shaft.removeGenerator(pos);
            CdgOverloadFuse.startFuse(level, pos, state);
            if (wasLatched != state.overloadLatched) {
                self.setChanged();
            }
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
        // 引信 tick 内的倒计时不标脏：崩溃窗口内至多丢 1 tick 倒计时，可接受
    }
}
