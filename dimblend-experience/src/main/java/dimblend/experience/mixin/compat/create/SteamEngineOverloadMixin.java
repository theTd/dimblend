package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.SteamEngineExhaustAccess;
import dimblend.experience.compat.create.SteamEngineOverload;
import dimblend.experience.compat.create.SteamEngineOverloadMath;
import net.minecraft.server.level.ServerLevel;

/**
 * H 板块蒸汽引擎过载两阶段：{@code SteamEngineBlockEntity.tick()} RETURN 注入
 * （原版 tick 服务端下半常跑，RETURN 即每服务端 tick 一次）。
 *
 * <p>状态机：</p>
 * <ul>
 * <li>排气中（{@link SteamEngineExhaustAccess} 里有剩余量，落盘可跨重进）：只推进排气
 * （过载解除不中断），剩余量每 tick -1；到 0 清状态 + {@link SteamEngineOverload#finishExhaust}
 * 把 32 秒里被"卡"在普通杆形态的轴换回动力轴 + {@code notifyUpdate()}，恢复正常运转。
 * 引擎被拆/区块卸载则 BE 不再 tick，重进后由 {@code read} 把剩余量带回来继续烧。</li>
 * <li>运转（isValid + 轴在位）且轴过载：overloadAge++，播警告粒子/音；
 * 满 320 tick（16 秒）→ {@link SteamEngineOverload#beginExhaust}（断轴 + 写 640 + 同步）。</li>
 * <li>其余（未运转/过载解除/轴缺失）：清过载年龄，第一阶段声音粒子同时停。</li>
 * </ul>
 *
 * <p>守卫口径：先 ServerLevel（双端方法），再总开关——关闭时清状态透传原版；
 * 客户端不读 SERVER 配置（客户端只通过 NBT 拿到剩余量）。</p>
 */
@Mixin(SteamEngineBlockEntity.class)
public abstract class SteamEngineOverloadMixin {

    /** 第一阶段过载年龄（tick，内存态；中断清零）。 */
    @Unique
    private int dimblend$overloadAge;

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1, allow = 8)
    private void dimblend$steamOverloadTwoStages(CallbackInfo ci) {
        SteamEngineBlockEntity self = (SteamEngineBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!Config.STEAM_ENGINE_OVERLOAD.get()) {
            this.dimblend$overloadAge = 0;
            if (SteamEngineExhaustAccess.isExhausting(self)) {
                SteamEngineExhaustAccess.clear(self);
                self.notifyUpdate();
            }
            return;
        }

        int remaining = SteamEngineExhaustAccess.remaining(self);
        if (remaining > 0) {
            // 第二阶段：过载解除不中断，按剩余量往下数（32 秒）
            int age = SteamEngineExhaustAccess.age(remaining);
            SteamEngineOverload.tickExhaust(level, self.getBlockPos(), age);
            this.dimblend$overloadAge = 0;
            int next = remaining - 1;
            if (next <= 0) {
                SteamEngineExhaustAccess.clear(self);
                SteamEngineOverload.finishExhaust(level, self.getBlockPos());
                self.notifyUpdate();
            } else {
                SteamEngineExhaustAccess.setRemaining(self, next);
            }
            return;
        }

        if (!SteamEngineOverload.isRunning(self)) {
            this.dimblend$overloadAge = 0;
            return;
        }
        PoweredShaftBlockEntity shaft = self.getShaft();
        if (shaft == null || !SteamEngineOverload.isOverloaded(shaft)) {
            // 过载解除/轴缺失：第一阶段清零，声音粒子同时停
            this.dimblend$overloadAge = 0;
            return;
        }
        int age = this.dimblend$overloadAge;
        if (SteamEngineOverloadMath.windowElapsed(age)) {
            SteamEngineOverload.beginExhaust(level, self.getBlockPos(), self);
            this.dimblend$overloadAge = 0;
            return;
        }
        SteamEngineOverload.tickOverload(level, self.getBlockPos(), age);
        this.dimblend$overloadAge = age + 1;
    }
}
