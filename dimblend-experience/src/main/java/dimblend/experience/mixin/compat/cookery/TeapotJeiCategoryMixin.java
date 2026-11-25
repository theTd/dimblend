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
 * 该类全类只有一个 {@code bipush 12}（javap 核实，运行 jar
 * {@code kaleidoscopecookery-1.4.1-neoforge+mc1.21.1.jar} 字节码偏移 44）。</p>
 *
 * <p>方法必须写<b>全描述符</b>：该类被 JEI 泛型擦除后还有一条桥
 * {@code setRecipe(IRecipeLayoutBuilder, Object, IFocusGroup)V}，裸 {@code "setRecipe"}
 * 会连桥方法一起选中，{@code allow = 1} 直接启动变红。真实三参形态是
 * {@code (Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/recipe/IFocusGroup;)V}。</p>
 *
 * <p>JEI 缺席时由插件门控跳过，故用 {@code targets} 字符串 + 类不在时整体不应用。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.compat.jei.category.TeapotRecipeCategory", remap = false)
public abstract class TeapotJeiCategoryMixin {

    @ModifyConstant(
            method = "setRecipe(Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/recipe/IFocusGroup;)V",
            constant = @Constant(intValue = 12),
            remap = false, require = 1, allow = 1)
    private static int dimblend$jeiFourServings(int original) {
        return TeapotServingRules.outputCount();
    }
}
