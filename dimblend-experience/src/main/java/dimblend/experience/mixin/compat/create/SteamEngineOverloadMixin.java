package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.SteamEngineOverload;
import dimblend.experience.compat.create.SteamEngineOverloadMath;
import net.minecraft.server.level.ServerLevel;

/**
 * H 板块蒸汽引擎过载两阶段：{@code SteamEngineBlockEntity.tick()} RETURN 注入
 * （原版 tick 服务端下半常跑，RETURN 即每服务端 tick 一次）。
 *
 * <p>状态机（全内存态，区块卸载/BE 移除即丢，重进重判）：</p>
 * <ul>
 * <li>排气中（exhausting）：只推进排气（过载解除不中断）；播满 8 秒清状态。
 * 引擎被拆时 BE 不再 tick，自然终止。</li>
 * <li>运转（isValid + 轴在位）且轴过载：overloadAge++，播警告粒子/音；
 * 满 320 tick（16 秒）→ 断轴（仍是动力轴才破、掉落传动杆）+ 转排气（当 tick 即播
 * 排气音 + 第一组粒子）。</li>
 * <li>其余（未运转/过载解除/轴缺失）：清过载年龄，第一阶段声音粒子同时停。</li>
 * </ul>
 *
 * <p>守卫口径：先 ServerLevel（双端方法），再总开关——关闭时清状态透传原版；
 * 客户端不读 SERVER 配置。开关热关闭时排气一并清（无幽灵粒子）。</p>
 */
@Mixin(SteamEngineBlockEntity.class)
public abstract class SteamEngineOverloadMixin {

    /** 第一阶段过载年龄（tick，内存态；中断清零）。 */
    @Unique
    private int dimblend$overloadAge;

    /** 第二阶段排气中（内存态；过载解除不中断，播满 8 秒清）。 */
    @Unique
    private boolean dimblend$exhausting;

    /** 第二阶段排气年龄（tick，内存态；断轴当 tick 为 0）。 */
    @Unique
    private int dimblend$exhaustAge;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void dimblend$steamOverloadTwoStages(CallbackInfo ci) {
        SteamEngineBlockEntity self = (SteamEngineBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!Config.STEAM_ENGINE_OVERLOAD.get()) {
            this.dimblend$overloadAge = 0;
            this.dimblend$exhausting = false;
            this.dimblend$exhaustAge = 0;
            return;
        }
        if (this.dimblend$exhausting) {
            // 第二阶段：过载解除不中断，只播满 8 秒
            if (SteamEngineOverload.tickExhaust(level, self.getBlockPos(), this.dimblend$exhaustAge)) {
                this.dimblend$exhausting = false;
                this.dimblend$exhaustAge = 0;
            } else {
                this.dimblend$exhaustAge++;
            }
            this.dimblend$overloadAge = 0;
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
            SteamEngineOverload.severShaft(level, self.getBlockPos());
            this.dimblend$exhausting = true;
            this.dimblend$exhaustAge = 0;
            this.dimblend$overloadAge = 0;
            return;
        }
        SteamEngineOverload.tickOverload(level, self.getBlockPos(), age);
        this.dimblend$overloadAge = age + 1;
    }
}
