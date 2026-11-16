package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.crafting.container.BarrelRecipeContainer;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.BarrelRecipe;
import dimblend.experience.compat.tavern.TavernBrewingRules;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Barrel recipe output: computes assembled stack size through {@link TavernBrewingRules}
 * (capped at 4 in both modes; count/4 in locked quarter-yield mode).
 *
 * <p>{@code matches} stays untouched so undersized valid recipes are recognized by
 * {@link BarrelBlockEntityMixin} and held idle rather than falling into the Vinegar fallback.</p>
 */
@Mixin(value = BarrelRecipe.class, remap = false)
public abstract class BarrelRecipeMixin {

    @Inject(method = "assemble(Lcom/github/ysbbbbbb/kaleidoscopetavern/crafting/container/BarrelRecipeContainer;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private void dimblend$assembleConfiguredCount(BarrelRecipeContainer container,
            HolderLookup.Provider registries, CallbackInfoReturnable<ItemStack> cir) {
        int count = TavernBrewingRules.computeAssembledCount(container.getItems());
        cir.setReturnValue(((BarrelRecipe) (Object) this).getResultItem(registries).copyWithCount(count));
    }
}
