package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * D6 马达护目镜"应力量"改为实际转速响应值（2026-09-23 新条目）。
 *
 * <p>字节码基线（运行 create jar 6.0.10-281，javap -v 核实）：
 * {@code GeneratingKineticBlockEntity.addToGoggleTooltip} 的容量行按
 * {@code stressBase *= getGeneratedSpeed() / speed; total = |stressBase × speed|}
 * 折算（getGeneratedSpeed Methodref owner=GeneratingKineticBlockEntity，
 * addToGoggleTooltip 内 offset 73/90 两处调用）。CCA 马达客户端
 * {@code getGeneratedSpeed() = convertToDirection(active ? motorSpeed : 0, facing)}
 * ——而 {@code active}/{@code motorSpeed} 均不在客户端同步包内（只写
 * writeSafe 不写 write，见 {@code ElectricMotorSoundClientMixin} 考证），
 * 客户端恒返回 0 → 只要 speed≠0 折算系数恒 ×0，<b>原版即显示"应力量：0 su"</b>。
 * D2 映射后更与网络实际容量（MAX_STRESS/256 × |实际转速|）全面脱节。</p>
 *
 * <p>修复：目标为本类全部发电机共用的折算入口，handler 以
 * instanceof 限定 CCA 电动马达 + 行为开关在场时把 getGeneratedSpeed 的读数
 * 替换为 {@code getTheoreticalSpeed()}（public，同步 "Speed" 字段原值——
 * 不用 @Shadow：mixin 的 findAliasedField 只解析目标类自声明字段，父类
 * KineticBlockEntity 的字段 shadow 会在 apply 期抛 InvalidMixinException，
 * 运行库 sponge-mixin 0.15.4+mixin.0.8.7 fork 及上游 0.8.x 字节码核实）
 * ——折算自比 speed/speed=1 跳过，显示 = MAX_STRESS/256 × |实际映射转速|，
 * 与 KineticNetwork 实际入网容量（sources.get × |getGeneratedSpeed()|，
 * 服务端口径）一致。其余发电机与开关关闭时透传原读数（保持原版行为，
 * 含上游 0 显示怪癖）。</p>
 */
@Mixin(GeneratingKineticBlockEntity.class)
public abstract class ElectricMotorGeneratorStatsMixin {

    @ModifyExpressionValue(
            method = "addToGoggleTooltip(Ljava/util/List;Z)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/kinetics/base/GeneratingKineticBlockEntity;getGeneratedSpeed()F",
                    remap = false))
    private float dimblend$motorGeneratedAtActualRpm(float original) {
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return original;
        }
        if (!((Object) this instanceof ElectricMotorBlockEntity)) {
            return original;
        }
        return ((GeneratingKineticBlockEntity) (Object) this).getTheoreticalSpeed();
    }
}
