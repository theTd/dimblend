package dimblend.experience.mixin.compat.cca;

import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
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
 * <p>接受边界（反拖场景）：马达被更强动力源反拖时实际转速≠motorSpeed，
 * 服务端实扣按 motorSpeed、本显示按轴速 |实际转速|——与 D4 音效的
 * 反拖边界同族，按"显示跟轴"取舍（见 spec D6）。</p>
 *
 * <p>开关关闭透传面板值（原版行为：不随信号变化、停转也显示面板值）。
 * 客户端读 SERVER config 经 NeoForge 内置 ConfigSync 登录同步（tickAudio 先例）。
 * 不用 @Shadow 读父类字段：mixin 的 findAliasedField 只解析目标类自声明字段
 * （运行库 sponge-mixin 0.15.4+mixin.0.8.7 fork 及上游 0.8.x 字节码核实，
 * 父类字段 shadow 在 apply 期抛 InvalidMixinException），故走公有 getter。</p>
 */
@Mixin(ElectricMotorBlockEntity.class)
public abstract class ElectricMotorGoggleMixin {

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
        return Math.abs(((ElectricMotorBlockEntity) (Object) this).getTheoreticalSpeed());
    }
}
