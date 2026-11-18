package dimblend.experience.compat.dirtywater;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

/**
 * 合成台 / 自动合成器 / 精致背包合成升级的脏水拒收规则。
 *
 * <p>落点是 {@code ShapedRecipe#matches} 与 {@code ShapelessRecipe#matches}：工作台的
 * {@code RecipeManager#getRecipeFor}、自动合成器的 {@code RecipeCache}、精致背包
 * {@code CraftingUpgradeContainer} 的 {@code lastRecipe.matches(...)} 旁路都会经过配方自己的
 * {@code matches}，一处拦截即可全覆盖（背包那条旁路绕开了 {@code RecipeManager}，这也是
 * 不选 {@code RecipeManager} 注入的原因）。</p>
 *
 * <p>只拒"输出是食物/饮品（含面团/汤/调饮）且网格里有 Purity 0 的水"的组合；
 * {@code fluid:copper_sink}、{@code splashing_sail}、{@code rainy_cloud}、
 * {@code clean_fabric_filter} 这类非食物配方照常接受脏水。</p>
 */
public final class DirtyWaterCraftingRules {

    /** 配方是否应被脏水拦下（由两个 {@code matches} mixin 调用）。 */
    public static boolean blocksRecipe(Recipe<?> recipe, CraftingInput input, Level level) {
        if (!DirtyWaterCompat.purityAvailable() || recipe == null || input == null || level == null) {
            return false;
        }
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (!DirtyWaterRules.isFoodOrDrink(result)) {
            return false;
        }
        return containsDirtyWater(input);
    }

    /** 合成输入（1×1/2×2/3×3、自动合成器、背包）里是否有 Purity 0 的水容器。 */
    public static boolean containsDirtyWater(CraftingInput input) {
        for (int slot = 0; slot < input.size(); slot++) {
            if (DirtyWaterPurity.isDirtyWaterItem(input.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 网格里既有脏水、又有别的物品——玩家确实在把脏水当原料用，才值得弹提示。
     *
     * <p>只放了一桶水、别的格子全空时，配方本来就不可能匹配，提示只会变成噪音。</p>
     */
    public static boolean looksLikeDirtyWaterIngredient(Container container) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return false;
        }
        boolean dirtyWater = false;
        int filled = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            filled++;
            if (DirtyWaterPurity.isDirtyWaterItem(stack)) {
                dirtyWater = true;
            }
        }
        return dirtyWater && filled >= 2;
    }

    private DirtyWaterCraftingRules() {
    }
}
