package dimblend.experience.mixin.compat.cookery;

import dimblend.experience.compat.cookery.TeapotServingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * EMI 茶壶配方页的产出格显示 4 杯（第 4 / 5 个硬编码点）。
 *
 * <p>12 落在私有静态 lambda {@code lambda$register$1} 里（{@code register} 本体的
 * {@code bipush 12} 也是这个 lambda 之前的同一个值，javap 核实全类只有一个 12）。
 * EMI 缺席时插件门控跳过。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.compat.emi.category.EmiTeapotRecipe", remap = false)
public abstract class TeapotEmiRecipeMixin {

    @ModifyConstant(method = "lambda$register$1", constant = @Constant(intValue = 12),
            remap = false, require = 1, allow = 1)
    private static int dimblend$emiFourServings(int original) {
        return TeapotServingRules.outputCount();
    }
}
