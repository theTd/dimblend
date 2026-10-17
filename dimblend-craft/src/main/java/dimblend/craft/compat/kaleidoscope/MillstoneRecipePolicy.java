package dimblend.craft.compat.kaleidoscope;

import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DyeItem;
import net.neoforged.neoforge.common.Tags;

/** 森罗石磨的展示与加工共用过滤；不修改 RecipeManager 或 Create 小石磨。 */
public final class MillstoneRecipePolicy {
    private MillstoneRecipePolicy() {
    }

    public static boolean excluded(MillstoneRecipe recipe) {
        var inputs = recipe.ingredient().getItems();
        boolean backpackInput = inputs.length > 0;
        for (var stack : inputs) {
            backpackInput &= BuiltInRegistries.ITEM.getKey(stack.getItem())
                    .getNamespace().equals("sophisticatedbackpacks");
        }
        if (backpackInput) {
            return true;
        }

        boolean hasOutput = false;
        for (var output : recipe.results()) {
            if (output.chance() <= 0 || output.stack().isEmpty()) {
                continue;
            }
            hasOutput = true;
            if (!(output.stack().getItem() instanceof DyeItem) && !output.stack().is(Tags.Items.DYES)) {
                return false;
            }
        }
        return hasOutput;
    }
}
