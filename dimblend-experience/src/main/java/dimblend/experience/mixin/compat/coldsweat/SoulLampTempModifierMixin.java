package dimblend.experience.mixin.compat.coldsweat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.momosoftworks.coldsweat.api.temperature.modifier.SoulLampTempModifier;

import dimblend.experience.compat.coldsweat.SoulspringLampRules;

/**
 * 灵魂之泉灯的降温强度下限 0.80（LOCKED DECISION #15）。
 *
 * <p>{@code SoulLampTempModifier#calculate} 的 lambda 里读
 * {@code ConfigSettings.SOULSPRING_LAMP_STRENGTH.get()}（{@code DynamicHolder<Double>}，
 * {@code javap -p} 核实 {@code get()} 的擦除返回是 {@code Object}），实际算式是
 * {@code temp * (1 - strength)}。原版默认 0.6 → 隔热 40%，本 mod 要求<b>至少</b> 0.80（隔热 20%，
 * 约 369°F 天花板）。</p>
 *
 * <p>做法是 max 夹紧在读取处，而<b>不</b>去改 {@code item.toml} 或 holder 的值：用户把强度
 * 调到 0.95 依旧生效（大于 0.80），调到 0.3 会被抬到 0.80。handler 返回 {@code Object} 是为了
 * 与 {@code get()} 的擦除描述符完全一致（后面原版自带 {@code CHECKCAST Double}）。</p>
 */
@Mixin(value = SoulLampTempModifier.class, remap = false)
public abstract class SoulLampTempModifierMixin {

    @ModifyExpressionValue(
            method = "calculate",
            at = @At(value = "INVOKE",
                    target = "Lcom/momosoftworks/coldsweat/util/serialization/DynamicHolder;get()Ljava/lang/Object;"),
            remap = false, require = 1, allow = 1)
    private static Object dimblend$strengthFloor(Object original) {
        double configured = original instanceof Number number
                ? number.doubleValue()
                : SoulspringLampRules.VANILLA_STRENGTH;
        return Double.valueOf(SoulspringLampRules.effectiveStrength(configured));
    }
}
