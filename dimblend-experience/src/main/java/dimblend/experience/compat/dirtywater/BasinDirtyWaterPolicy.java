package dimblend.experience.compat.dirtywater;

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Create 工作盆（{@code create:basin}）的脏水策略：读方块实体 → 交给
 * {@link BasinDirtyWaterIntent} 判定。
 *
 * <p>纯判定在 {@link BasinDirtyWaterIntent}（单测入口）；本类只做 Create/注册表接触与
 * "输入罐里是否已有脏水"的检查，供 {@code BasinFluidGuard}（fill 闸门）与
 * {@code BasinRecipeDirtyWaterMixin}（配方级保底）共用。</p>
 */
public final class BasinDirtyWaterPolicy {

    /** 物品三分法：药水优先，其次食物/饮品/面团/酿造原料。 */
    public static BasinDirtyWaterIntent.ItemKind classify(ItemStack stack) {
        if (stack.isEmpty()) {
            return BasinDirtyWaterIntent.ItemKind.OTHER;
        }
        if (DirtyWaterRules.isPotion(stack) || stack.has(DataComponents.POTION_CONTENTS)) {
            return BasinDirtyWaterIntent.ItemKind.POTION;
        }
        return DirtyWaterRules.isFoodLike(stack)
                ? BasinDirtyWaterIntent.ItemKind.FOOD
                : BasinDirtyWaterIntent.ItemKind.OTHER;
    }

    /** 读方块实体的过滤槽与输入库存，得到当前意图。 */
    public static BasinDirtyWaterIntent.Intent intentOf(BasinBlockEntity basin) {
        FilteringBehaviour filtering = basin.getFilter();
        ItemStack filterStack = filtering == null ? ItemStack.EMPTY : filtering.getFilter();

        boolean basinEmpty = true;
        boolean anyPotion = false;
        boolean anyFoodLike = false;
        for (int slot = 0; slot < basin.inputInventory.getSlots(); slot++) {
            ItemStack stack = basin.inputInventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            basinEmpty = false;
            BasinDirtyWaterIntent.ItemKind kind = classify(stack);
            if (kind == BasinDirtyWaterIntent.ItemKind.POTION) {
                anyPotion = true;
            } else if (kind == BasinDirtyWaterIntent.ItemKind.FOOD) {
                anyFoodLike = true;
            }
        }

        return BasinDirtyWaterIntent.resolve(
                filterStack.isEmpty(), classify(filterStack), basinEmpty, anyPotion, anyFoodLike);
    }

    /** 该工作盆是否允许脏水进入（输入罐 fill 与配方级保底共用）。 */
    public static boolean allowsDirtyWater(BasinBlockEntity basin) {
        return BasinDirtyWaterIntent.allowsDirtyWater(intentOf(basin));
    }

    /** 输入罐里是否已经有脏水（配方级保底用；水进罐后过滤槽可能已改变）。 */
    public static boolean hasDirtyWater(BasinBlockEntity basin) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return false;
        }
        IFluidHandler input = basin.inputTank.getCapability();
        for (int tank = 0; tank < input.getTanks(); tank++) {
            if (DirtyWaterPurity.isDirtyWaterFluid(input.getFluidInTank(tank))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配方级保底：盆里已是脏水时，食物/饮品配方一律不启动。
     *
     * <p>覆盖"脏水先进了罐、面粉后扔进来"的路径；{@code create:potion} 的
     * {@code CreatePotionRecipe} 不走 BasinRecipe 的 apply，天然不受影响。</p>
     */
    public static boolean blocksRecipe(BasinBlockEntity basin, Recipe<?> recipe) {
        Level level = basin.getLevel();
        if (level == null || recipe == null || !hasDirtyWater(basin)) {
            return false;
        }
        if (DirtyWaterRules.isFoodOrDrink(recipe.getResultItem(level.registryAccess()))) {
            return true;
        }
        if (recipe instanceof BasinRecipe basinRecipe) {
            for (var fluidResult : basinRecipe.getFluidResults()) {
                if (DirtyWaterRules.isDrinkFluid(fluidResult)) {
                    return true;
                }
            }
        }
        return false;
    }

    private BasinDirtyWaterPolicy() {
    }
}
