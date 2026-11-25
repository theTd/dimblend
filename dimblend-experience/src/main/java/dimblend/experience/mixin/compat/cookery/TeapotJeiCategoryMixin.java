package dimblend.experience.mixin.compat.cookery;

import dimblend.experience.compat.cookery.TeapotServingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * JEI 茶壶配方页的产出格显示 4 杯（第 3 / 5 个硬编码点）。
 *
 * <p>{@code TeapotRecipeCategory#setRecipe} 里 {@code output.copyWithCount(12)}；
 * 该类全类只有一个 {@code bipush 12}（javap 核实）。JEI 缺席时由插件门控跳过，
 * 故用 {@code targets} 字符串 + 类不在时整体不应用。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.compat.jei.category.TeapotRecipeCategory", remap = false)
public abstract class TeapotJeiCategoryMixin {

    @ModifyConstant(method = "setRecipe", constant = @Constant(intValue = 12),
            remap = false, require = 1, allow = 1)
    private static int dimblend$jeiFourServings(int original) {
        return TeapotServingRules.outputCount();
    }
}
