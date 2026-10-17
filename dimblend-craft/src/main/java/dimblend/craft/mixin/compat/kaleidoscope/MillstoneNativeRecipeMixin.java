package dimblend.craft.mixin.compat.kaleidoscope;

import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.craft.compat.kaleidoscope.MillstoneRecipePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 禁配方不参与原生匹配，使 Create 的合法混合产物兜底仍可被找到。 */
@Mixin(MillstoneRecipe.class)
public abstract class MillstoneNativeRecipeMixin {
    @ModifyReturnValue(method = "matches(Lcom/github/ysbbbbbb/kaleidoscopecookery/crafting/container/SimpleInput;Lnet/minecraft/world/level/Level;)Z",
            at = @At("RETURN"), require = 1, allow = 1)
    private boolean dimblend$skipExcludedNativeRecipe(boolean original) {
        return original && !MillstoneRecipePolicy.excluded((MillstoneRecipe) (Object) this);
    }
}
