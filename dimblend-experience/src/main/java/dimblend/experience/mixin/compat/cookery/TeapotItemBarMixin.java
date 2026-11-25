package dimblend.experience.mixin.compat.cookery;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dimblend.experience.compat.cookery.TeapotServingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 茶壶物品条按 4 杯归一（第 2 / 5 个硬编码点）。
 *
 * <p>原版 {@code getBarWidth} 是 {@code Math.round(13.0f * count / 12.0f)}：分母
 * {@code 12.0f} 改成 {@link TeapotServingRules#BAR_DIVISOR}；旧存档 count 可能仍 &gt; 4
 * （历史数据 12 杯），所以返回前再用 {@link TeapotServingRules#clampBarWidth} 夹到
 * {@code [0, 13]}，避免条画到框外。</p>
 *
 * <p>{@code TeapotItem#getBarWidth} 有 <b>6</b> 个 {@code ireturn}（运行 jar
 * {@code kaleidoscopecookery-1.4.1-neoforge+mc1.21.1.jar} 字节码偏移 16 / 70 / 73 / 100 / 115 / 117）：
 * 分别是 CustomData 为空返回 0、空茶汤返回 0、{@code Status == 1} 返回 13、
 * {@code count <= 0} 返回 0、{@code Math.round(...)} 的正常宽度、{@code Status} 落到
 * 未处理分支返回 0。其中 0 与 13 对 {@code clampBarWidth} 幂等，只有
 * {@code Math.round(...)} 那条可能溢出上限，所以 {@code require = 6, allow = 6}
 * 把 6 个返回点全部显式钉死（漏钉就只剩部分返回点被夹）。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.item.TeapotItem", remap = false)
public abstract class TeapotItemBarMixin {

    @ModifyConstant(method = "getBarWidth", constant = @Constant(floatValue = 12.0f),
            remap = false, require = 1, allow = 1)
    private static float dimblend$fourServingDivisor(float original) {
        return TeapotServingRules.BAR_DIVISOR;
    }

    @ModifyReturnValue(method = "getBarWidth", at = @At("RETURN"), remap = false, require = 6, allow = 6)
    private static int dimblend$clampBarWidth(int original) {
        return TeapotServingRules.clampBarWidth(original);
    }
}
