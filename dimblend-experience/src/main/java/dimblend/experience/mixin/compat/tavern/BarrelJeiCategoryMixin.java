package dimblend.experience.mixin.compat.tavern;

import dimblend.experience.compat.tavern.TavernBrewingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * JEI barrel preview: input stacks and the result stack both used the vanilla 16. Pseudo-mixin
 * so the module does not need a compile dependency on JEI (the target is skipped when absent).
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopetavern.compat.jei.category.BarrelRecipeCategory",
        remap = false)
public abstract class BarrelJeiCategoryMixin {

    @ModifyConstant(method = "lambda$setRecipe$0", constant = @Constant(intValue = 16),
            remap = false, require = 1, allow = 1)
    private static int dimblend$jeiInputCount(int original) {
        return TavernBrewingRules.viewerInputCount();
    }

    @ModifyConstant(
            method = "setRecipe(Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/recipe/IFocusGroup;)V",
            constant = @Constant(intValue = 16), remap = false, require = 1, allow = 1)
    private int dimblend$jeiOutputCount(int original) {
        return TavernBrewingRules.outputCount();
    }
}
