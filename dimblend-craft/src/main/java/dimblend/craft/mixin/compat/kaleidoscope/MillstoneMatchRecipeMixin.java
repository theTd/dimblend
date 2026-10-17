package dimblend.craft.mixin.compat.kaleidoscope;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.craft.compat.kaleidoscope.MillstoneRecipePolicy;
import java.util.Optional;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在原生匹配与所有 Post 兼容回调完成后统一限制，避免 Create 兜底重新补回。 */
@Mixin(MillstoneBlockEntity.class)
public abstract class MillstoneMatchRecipeMixin {
    @ModifyReturnValue(method = "matchRecipe", at = @At("RETURN"), require = 1, allow = 1)
    private Optional<RecipeHolder<MillstoneRecipe>> dimblend$filterMatchedRecipe(
            Optional<RecipeHolder<MillstoneRecipe>> original) {
        return original.filter(holder -> !MillstoneRecipePolicy.excluded(holder.value()));
    }
}
