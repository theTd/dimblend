package dimblend.craft.recipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;

import dimblend.craft.DimblendCraft;

/**
 * 配方编辑执行器：在数据包重载的 apply 阶段对 {@link RecipeManager} 做单趟
 * 「删除 / 网格修改」处理，最后经原版 {@link RecipeManager#replaceRecipes} 原子生效。
 *
 * <p>执行器本身无状态：规则是纯数据，每次重载（初始加载、/reload）都从零重放，
 * 天然幂等。目标 mod 缺失的规则不产生任何改动，只输出未命中告警。</p>
 */
public final class RecipeEditApplicator {

    private RecipeEditApplicator() {
    }

    public static void apply(RecipeManager recipeManager, HolderLookup.Provider registries,
            List<RecipeEdit> edits) {
        Map<RecipeEdit, Stats> statsByIdentity = new IdentityHashMap<>();
        edits.forEach(edit -> statsByIdentity.put(edit, new Stats()));

        List<RecipeHolder<?>> keptRecipes = new ArrayList<>(recipeManager.getRecipes().size());
        int removedCount = 0;
        int modifiedCount = 0;

        for (RecipeHolder<?> holder : List.copyOf(recipeManager.getRecipes())) {
            Recipe<?> recipe = holder.value();
            // 产物解析失败（如某下游配方 getResultItem 抛异常）时保留该配方、仅告警，
            // 避免单条异常配方中断整轮 apply。
            ResourceLocation resultId;
            try {
                resultId = resultIdOf(recipe, registries);
            } catch (Exception e) {
                DimblendCraft.LOGGER.warn("[配方] 解析配方 {} 的产物失败，保留原样", holder.id(), e);
                keptRecipes.add(holder);
                continue;
            }

            RecipeEdit deletion = matchingDelete(holder.id(), resultId, recipe, edits);
            if (deletion != null) {
                Stats stats = statsByIdentity.get(deletion);
                stats.matchedIds.add(holder.id());
                removedCount++;
                DimblendCraft.LOGGER.info("[配方] 删除 {}（规则：{}）", holder.id(), deletion.label());
                continue;
            }

            if (recipe instanceof ShapedRecipe shaped) {
                RecipeEdit.ModifyShapedGrid modify = matchingModify(resultId, edits);
                if (modify != null) {
                    Stats stats = statsByIdentity.get(modify);
                    RecipeHolder<?> edited = applyModify(holder, shaped, modify, registries, stats);
                    if (edited != null) {
                        modifiedCount++;
                        keptRecipes.add(edited);
                        continue;
                    }
                }
            }

            keptRecipes.add(holder);
        }

        if (removedCount > 0 || modifiedCount > 0) {
            recipeManager.replaceRecipes(keptRecipes);
            DimblendCraft.LOGGER.info("[配方] 本轮编辑完成：删除 {} 条，修改 {} 条", removedCount, modifiedCount);
        }
        warnUnmatched(edits, statsByIdentity);
    }

    /** 规则删除命中判定：按配方 ID（任意类型）→ 按产物 ID（合成台配方 + 规则额外指定的配方类型）。 */
    private static RecipeEdit matchingDelete(ResourceLocation recipeId, ResourceLocation resultId,
            Recipe<?> recipe, List<RecipeEdit> edits) {
        for (RecipeEdit edit : edits) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete && delete.recipeIds().contains(recipeId)) {
                return edit;
            }
            if (edit instanceof RecipeEdit.DeleteByResultId delete
                    && resultId != null
                    && delete.resultIds().matches(resultId)
                    && matchesType(recipe, delete.recipeTypeIds())) {
                return edit;
            }
        }
        return null;
    }

    /**
     * 产物删除规则的类型命中判定：合成台配方恒匹配（沿用原语义，含机械合成等实现类），
     * 其余类型按配方类型注册表键匹配，避免编译期依赖上游 mod 类。
     */
    private static boolean matchesType(Recipe<?> recipe, Set<ResourceLocation> recipeTypeIds) {
        if (recipe instanceof CraftingRecipe) {
            return true;
        }
        if (recipeTypeIds.isEmpty()) {
            return false;
        }
        ResourceLocation typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return typeKey != null && recipeTypeIds.contains(typeKey);
    }

    private static RecipeEdit.ModifyShapedGrid matchingModify(ResourceLocation resultId,
            List<RecipeEdit> edits) {
        if (resultId == null) {
            return null;
        }
        for (RecipeEdit edit : edits) {
            if (edit instanceof RecipeEdit.ModifyShapedGrid modify && modify.resultTargets().contains(resultId)) {
                return modify;
            }
        }
        return null;
    }

    private static RecipeHolder<?> applyModify(RecipeHolder<?> holder, ShapedRecipe shaped,
            RecipeEdit.ModifyShapedGrid modify, HolderLookup.Provider registries, Stats stats) {
        if (modify.slotEdits().isEmpty()) {
            ShapedRecipe edited = ShapedGridEditor.withResultCount(shaped, modify.newResultCount(), registries);
            stats.matchedIds.add(holder.id());
            DimblendCraft.LOGGER.info("[配方] 修改 {}：产物数量 -> {}（规则：{}）",
                    holder.id(), modify.newResultCount(), modify.label());
            return new RecipeHolder<>(holder.id(), edited);
        }
        Optional<ShapedRecipe> edited = ShapedGridEditor.edit(shaped, modify.slotEdits(), modify.newResultCount(),
                registries);
        if (edited.isEmpty()) {
            DimblendCraft.LOGGER.error("[配方] 规则「{}」无法应用于配方 {}（材料在物品注册表中不存在，"
                    + "或原图案超出 3×3），配方保持原样", modify.label(), holder.id());
            return null;
        }
        stats.matchedIds.add(holder.id());
        DimblendCraft.LOGGER.info("[配方] 修改 {}：{}（规则：{}）",
                holder.id(), describeSlotEdits(modify), modify.label());
        return new RecipeHolder<>(holder.id(), edited.get());
    }

    private static String describeSlotEdits(RecipeEdit.ModifyShapedGrid modify) {
        StringBuilder description = new StringBuilder();
        modify.slotEdits().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (description.length() > 0) {
                        description.append("，");
                    }
                    description.append("槽位 ").append(entry.getKey()).append(" -> ")
                            .append(entry.getValue().describe());
                });
        if (modify.newResultCount() != null) {
            description.append("，产物数量 -> ").append(modify.newResultCount());
        }
        return description.toString();
    }

    private static ResourceLocation resultIdOf(Recipe<?> recipe, HolderLookup.Provider registries) {
        ItemStack result = recipe.getResultItem(registries);
        return result.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(result.getItem());
    }

    private static void warnUnmatched(List<RecipeEdit> edits, Map<RecipeEdit, Stats> statsByIdentity) {
        for (RecipeEdit edit : edits) {
            if (!statsByIdentity.get(edit).matchedIds.isEmpty()) {
                continue;
            }
            if (edit instanceof RecipeEdit.DeleteByResultId delete && delete.defensive()) {
                DimblendCraft.LOGGER.info("[配方] 防御性规则「{}」未命中任何配方（属预期；"
                        + "若上游更新后出现该产物的配方将被自动删除）", edit.label());
            } else {
                DimblendCraft.LOGGER.warn("[配方] 规则「{}」未命中任何配方，整合包更新可能导致规则失效，"
                        + "请核对 docs/配方修改需求.md", edit.label());
            }
        }
    }

    /** 单条规则的命中记录（以规则实例为 IdentityHashMap 键，规则实例唯一）。 */
    private static final class Stats {
        private final Set<ResourceLocation> matchedIds = new HashSet<>(4);
    }
}