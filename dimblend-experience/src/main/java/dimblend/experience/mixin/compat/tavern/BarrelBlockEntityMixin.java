package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.container.BarrelRecipeContainer;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.BarrelRecipe;
import dimblend.experience.compat.tavern.TavernBrewingRules;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caps the unmatched-recipe Vinegar fallback to 4 items and holds fermentation idle when a
 * valid recipe is still below the 4-per-slot threshold in quarter-yield mode (so closing the
 * lid early never consumes 4 fluid buckets into Vinegar).
 */
@Mixin(value = BarrelBlockEntity.class, remap = false)
public abstract class BarrelBlockEntityMixin {

    @Shadow(remap = false)
    @Final
    private RecipeManager.CachedCheck<BarrelRecipeContainer, BarrelRecipe> quickCheck;

    @Inject(
            method = "tick(Lnet/minecraft/world/level/Level;)V",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;)Lcom/github/ysbbbbbb/kaleidoscopetavern/crafting/container/BarrelRecipeContainer;"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$holdIncompleteQuarterBatch(Level level, CallbackInfo ci) {
        if (TavernBrewingRules.shouldHoldIncompleteRecipeBatch(
                (BarrelBlockEntity) (Object) this, this.quickCheck, level)) {
            ci.cancel();
        }
    }

    @ModifyConstant(method = "lambda$tick$1()V", constant = @Constant(intValue = 16),
            remap = false, require = 1, allow = 1)
    private int dimblend$capFallbackVinegar(int original) {
        return TavernBrewingRules.outputCount();
    }
}
