package dimblend.craft.mixin.compat.kaleidoscope;

import com.github.ysbbbbbb.kaleidoscopecookery.compat.create.CreateCompat;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe;
import dimblend.craft.compat.kaleidoscope.MillstoneRecipePolicy;
import java.util.List;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 整包 JEI 使用的森罗列表入口，在 Create 转换追加后过滤原生及兜底条目。 */
@Mixin(CreateCompat.class)
public abstract class MillstoneSearchRecipeMixin {
    @Inject(method = "getTransformRecipeForSearch", at = @At("RETURN"), require = 1, allow = 1)
    private static void dimblend$filterSearchRecipes(Level level, List<RecipeHolder<MillstoneRecipe>> recipes,
            CallbackInfo ci) {
        recipes.removeIf(holder -> MillstoneRecipePolicy.excluded(holder.value()));
    }
}
