package dimblend.craft.recipe;

import java.util.Map;
import java.util.WeakHashMap;
import dimblend.craft.DimblendCraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** Ingredient predicates must run after this reload binds tags, before recipe caches and player sync. */
@EventBusSubscriber(modid = DimblendCraft.MODID)
public final class RecipeIngredientReloadFilter {
    private static final Map<RecipeManager, HolderLookup.Provider> PENDING = new WeakHashMap<>();
    private RecipeIngredientReloadFilter() {}

    public static synchronized void defer(RecipeManager recipes, HolderLookup.Provider registries) {
        PENDING.put(recipes, registries);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static synchronized void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
        var pending = Map.copyOf(PENDING);
        PENDING.clear();
        pending.forEach((recipes, registries) -> RecipeEditApplicator.apply(recipes, registries,
                RecipeEditRules.ingredientEdits()));
    }
}
