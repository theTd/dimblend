package dimblend.experience.mixin.compat.cca;

import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cca.MotorOverstressLatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
/**
 * D6 马达护目镜"已使用的能量"改为实际转速响应值（2026-09-23 新条目）。
 *
 * <p>字节码基线（运行 jar 1.5.10，javap -v 常量池实读）：{@code addToGoggleTooltip}
 * 内能耗行以 {@code getEnergyConsumptionRate(generatedSpeed.getValue())} 取数
 * （offset 31-39，invokestatic #215 owner=ElectricMotorBlockEntity，唯一调用点）
 * ——按<b>面板设定值</b>计，D2 映射后与实际耗电脱节。本 mixin 以 ModifyArg
 * 直接替换该静态调用的 float 实参为 {@code |getTheoreticalSpeed()|}
 * （public，返回经 "Speed" 标签同步的映射后实际转速原值；见
 * {@code ElectricMotorSoundClientMixin} 的同步链考证）。float 直通无截断：
 * 静态方法内的 D3 钳定（&lt;4 按 4 计 + 去下限）对显示值同样生效——
 * 自驱动场景下与服务端 tick 实扣（同函数、同入参口径）完全一致；
 * 无信号实际转速 0 → 显示 0。</p>
 *
 * <p>D7 锁存期护目镜“已使用的能量”×2：同一 handler 内对换算后的 |实际转速| 翻倍——
 * 与服务端 tick 实扣（冻结 rate×2，见
 * {@code ElectricMotorMixin#dimblend$doubleConsumptionWhenLatched}）同口径。
 * 锁存标志纯服务端内存不进同步包，客户端用三项已同步状态派生等价判据
 * （{@link MotorOverstressLatch#clientDerived}：过载 + |面板|&gt;64 +
 * 理论转速非零；Network/Speed/behaviour 三标签全同步，见 D4 考证）。
 * 面板读数走 @Shadow generatedSpeed（同文件 D6 已有先例的目标类自声明字段）。
 * 未锁存按原值显示。</p>
 *
 * <p>开关关闭透传面板值（原版行为：不随信号变化、停转也显示面板值）。
 * 客户端读 SERVER config 经 NeoForge 内置 ConfigSync 登录同步（tickAudio 先例）。
 * 不用 @Shadow 读父类字段：mixin 的 findAliasedField 只解析目标类自声明字段
 * （运行库 sponge-mixin 0.15.4+mixin.0.8.7 fork 及上游 0.8.x 字节码核实，
 * 父类字段 shadow 在 apply 期抛 InvalidMixinException），故走公有 getter。</p>
 */
@Mixin(ElectricMotorBlockEntity.class)
public abstract class ElectricMotorGoggleMixin {

    @Shadow(remap = false)
    protected com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour generatedSpeed;

    @ModifyArg(
            method = "addToGoggleTooltip(Ljava/util/List;Z)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mrh0/createaddition/blocks/electric_motor/ElectricMotorBlockEntity;getEnergyConsumptionRate(F)I",
                    remap = false),
            index = 0)
    private float dimblend$consumptionAtActualRpm(float panelRpm) {
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return panelRpm;
        }
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        float actual = Math.abs(self.getTheoreticalSpeed());
        if (MotorOverstressLatch.clientDerived(self.isOverStressed(),
                this.generatedSpeed.getValue(), self.getTheoreticalSpeed())) {
            return actual * 2.0F;
        }
        return actual;
    }
}
