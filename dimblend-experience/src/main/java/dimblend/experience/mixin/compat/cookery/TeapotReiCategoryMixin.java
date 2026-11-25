package dimblend.experience.mixin.compat.cookery;

import dimblend.experience.compat.cookery.TeapotServingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * REI 茶壶配方页的产出格显示 4 杯（第 5 / 5 个硬编码点）。
 *
 * <p>12 落在私有静态 lambda {@code lambda$registerDisplays$2} 里；REI 缺席时插件门控跳过。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.compat.rei.category.ReiTeapotRecipeCategory", remap = false)
public abstract class TeapotReiCategoryMixin {

    @ModifyConstant(method = "lambda$registerDisplays$2", constant = @Constant(intValue = 12),
            remap = false, require = 1, allow = 1)
    private static int dimblend$reiFourServings(int original) {
        return TeapotServingRules.outputCount();
    }
}
