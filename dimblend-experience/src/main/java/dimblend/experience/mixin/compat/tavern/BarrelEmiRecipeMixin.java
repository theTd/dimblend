package dimblend.experience.mixin.compat.tavern;

import dimblend.experience.compat.tavern.TavernBrewingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * EMI barrel preview: both the ingredient amount and the output amount are encoded as a
 * {@code long 16}. Pseudo-mixin (no EMI compile dependency).
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopetavern.compat.emi.category.EmiBarrelRecipe",
        remap = false)
public abstract class BarrelEmiRecipeMixin {

    @ModifyConstant(method = "lambda$register$0", constant = @Constant(longValue = 16L),
            remap = false, require = 1, allow = 1)
    private static long dimblend$emiInputCount(long original) {
        return TavernBrewingRules.viewerInputCount();
    }

    @ModifyConstant(method = "lambda$register$1", constant = @Constant(longValue = 16L),
            remap = false, require = 1, allow = 1)
    private static long dimblend$emiOutputCount(long original) {
        return TavernBrewingRules.outputCount();
    }
}
