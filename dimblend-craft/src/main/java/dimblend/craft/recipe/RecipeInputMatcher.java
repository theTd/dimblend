package dimblend.craft.recipe;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dimblend.craft.DimblendCraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/** Input-only matching after tag binding, using the complete Ingredient predicate. */
public final class RecipeInputMatcher {
    private RecipeInputMatcher() {}

    public static boolean consumes(Recipe<?> recipe, ResourceLocation itemId, HolderLookup.Provider registries) {
        var item = BuiltInRegistries.ITEM.getOptional(itemId);
        if (item.isEmpty()) return false;
        ItemStack target = new ItemStack(item.get());
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (acceptsItem(ingredient, target)) return true;
            }
            // Sequenced assembly's internal deployer inputs are absent from getIngredients().
            var encoded = Recipe.CODEC.encodeStart(ops, recipe).result();
            if (encoded.isEmpty()) {
                DimblendCraft.LOGGER.warn("Cannot inspect nested inputs of recipe {}", recipe.getClass().getName());
                return false;
            }
            return nestedInputs(encoded.get(), target, registries);
        } catch (RuntimeException exception) {
            DimblendCraft.LOGGER.warn("Cannot inspect inputs of recipe {}", recipe.getClass().getName(), exception);
            return false;
        }
    }

    private static boolean acceptsItem(Ingredient ingredient, ItemStack target) {
        if (ingredient.test(target)) return true;
        // Component-sensitive ingredients may accept a configured stack of the same item.
        for (ItemStack candidate : ingredient.getItems()) {
            if (candidate.is(target.getItem()) && ingredient.test(candidate)) return true;
        }
        return false;
    }

    private static boolean nestedInputs(JsonElement json, ItemStack target, HolderLookup.Provider registries) {
        if (json.isJsonArray()) {
            for (var child : json.getAsJsonArray()) if (nestedInputs(child, target, registries)) return true;
        } else if (json.isJsonObject()) {
            for (var entry : json.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                if (key.equals("result") || key.equals("results") || key.equals("transitional_item")) continue;
                if (key.equals("ingredient") || key.equals("ingredients") || key.equals("key")) {
                    if (matchesInput(entry.getValue(), target, registries)) return true;
                    // A false custom predicate must not be reinterpreted as the union of its children.
                    continue;
                }
                if (nestedInputs(entry.getValue(), target, registries)) return true;
            }
        }
        return false;
    }

    private static boolean matchesInput(JsonElement json, ItemStack target, HolderLookup.Provider registries) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var ingredient = Ingredient.CODEC.parse(ops, json).result();
        if (ingredient.isPresent()) return acceptsItem(ingredient.get(), target);
        // Shaped keys and lists of inputs may be containers rather than an Ingredient.
        if (json.isJsonArray()) {
            for (var child : json.getAsJsonArray()) if (matchesInput(child, target, registries)) return true;
        } else if (json.isJsonObject() && !json.getAsJsonObject().has("type")) {
            for (var child : json.getAsJsonObject().asMap().values()) {
                if (matchesInput(child, target, registries)) return true;
            }
        }
        return false;
    }

}
