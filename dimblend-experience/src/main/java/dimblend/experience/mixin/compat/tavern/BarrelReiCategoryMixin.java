package dimblend.experience.mixin.compat.tavern;

import dimblend.experience.compat.tavern.TavernBrewingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * REI barrel preview: input and output amounts are both {@code bipush 16}. Pseudo-mixin so REI
 * stays an optional runtime dependency.
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopetavern.compat.rei.category.ReiBarrelRecipeCategory",
        remap = false)
public abstract class BarrelReiCategoryMixin {

    @ModifyConstant(method = "lambda$registerDisplays$1", constant = @Constant(intValue = 16),
            remap = false, require = 1, allow = 1)
    private static int dimblend$reiInputCount(int original) {
        return TavernBrewingRules.viewerInputCount();
    }

    @ModifyConstant(method = "lambda$registerDisplays$2", constant = @Constant(intValue = 16),
            remap = false, require = 1, allow = 1)
    private static int dimblend$reiOutputCount(int original) {
        return TavernBrewingRules.outputCount();
    }
}
