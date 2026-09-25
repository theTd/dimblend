package dimblend.craft.recipe;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/**
 * 有序合成配方（ShapedRecipe）的 3×3 网格编辑器。
 *
 * <p>位置语义与需求文档第 2 节一致：槽位 1-9 按行优先编号，配方在 3×3 网格中
 * 居中摆放（与 JEI/配方书的显示一致）。窄于 3×3 的配方（如 Create 动力冲压机的
 * 1 宽立柱）居中后空缺的槽位视为空格，可被「添加」类规则填充。</p>
 */
public final class ShapedGridEditor {

    private static final int GRID_SIZE = 3;
    private static final char EMPTY_SYMBOL = ' ';
    private static final String SYMBOL_PALETTE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private ShapedGridEditor() {
    }

    /**
     * 按编辑表重建配方。
     *
     * @return 编辑后的新 ShapedRecipe（沿用原配方 ID 由调用方处理）；
     *         材料解析失败（物品未注册）或原图案超出 3×3 时返回 empty，调用方应放弃本条修改
     */
    public static Optional<ShapedRecipe> edit(ShapedRecipe recipe, Map<Integer, RecipeIngredient> slotEdits,
            Integer newResultCount, HolderLookup.Provider registries) {
        Ingredient[] grid = expandToFullGrid(recipe.pattern);
        if (grid == null) {
            return Optional.empty();
        }
        for (Map.Entry<Integer, RecipeIngredient> entry : slotEdits.entrySet()) {
            Optional<Ingredient> resolved = entry.getValue().resolve();
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            grid[gridIndex(entry.getKey())] = resolved.get();
        }

        ShapedRecipePattern rebuiltPattern = rebuildPattern(grid);
        ItemStack result = recipe.getResultItem(registries);
        if (newResultCount != null) {
            result = result.copyWithCount(newResultCount);
        }
        return Optional.of(new ShapedRecipe(recipe.getGroup(), recipe.category(), rebuiltPattern, result,
                recipe.showNotification()));
    }

    /** 仅改产物数量、材料布局不变。 */
    public static ShapedRecipe withResultCount(ShapedRecipe recipe, int newResultCount,
            HolderLookup.Provider registries) {
        ItemStack result = recipe.getResultItem(registries).copyWithCount(newResultCount);
        return new ShapedRecipe(recipe.getGroup(), recipe.category(), recipe.pattern, result,
                recipe.showNotification());
    }

    /**
     * 把原配方图案展开成 3×3 网格（行优先下标 0-8），其余槽位留空；
     * 图案超出 3×3（大工作台类 mod）时返回 null 表示无法按槽位编辑。
     */
    private static Ingredient[] expandToFullGrid(ShapedRecipePattern pattern) {
        int width = pattern.width();
        int height = pattern.height();
        if (width > GRID_SIZE || height > GRID_SIZE) {
            return null;
        }
        int offsetX = (GRID_SIZE - width) / 2;
        int offsetY = (GRID_SIZE - height) / 2;
        Ingredient[] grid = new Ingredient[GRID_SIZE * GRID_SIZE];
        Arrays.fill(grid, Ingredient.EMPTY);
        NonNullList<Ingredient> ingredients = pattern.ingredients();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                grid[(row + offsetY) * GRID_SIZE + (col + offsetX)] = ingredients.get(row * width + col);
            }
        }
        return grid;
    }

    /** 槽位 1-9 → 行优先 0-8 下标；越界槽位属于规则表错误，直接抛出。 */
    private static int gridIndex(int slot) {
        if (slot < 1 || slot > GRID_SIZE * GRID_SIZE) {
            throw new IllegalArgumentException("配方槽位超出 3x3 范围: " + slot);
        }
        return slot - 1;
    }

    /**
     * 由 3×3 材料网格重建 ShapedRecipePattern。
     *
     * <p>为每个非空材料分配一个符号（同一 Ingredient 实例共享符号），空槽位使用空格，
     * 最终经 {@link ShapedRecipePattern#of} 走原版解析流程：编辑后四周仍留空的配方
     * 会重新收缩为紧凑模式，与数据包手写行为一致。</p>
     */
    private static ShapedRecipePattern rebuildPattern(Ingredient[] grid) {
        Map<Ingredient, Character> assignedSymbols = new IdentityHashMap<>();
        Map<Character, Ingredient> key = new LinkedHashMap<>();
        StringBuilder[] lines = {
                new StringBuilder(GRID_SIZE), new StringBuilder(GRID_SIZE), new StringBuilder(GRID_SIZE)
        };
        for (int i = 0; i < grid.length; i++) {
            int row = i / GRID_SIZE;
            Ingredient ingredient = grid[i];
            if (ingredient == Ingredient.EMPTY) {
                lines[row].append(EMPTY_SYMBOL);
                continue;
            }
            Character symbol = assignedSymbols.get(ingredient);
            if (symbol == null) {
                int ordinal = assignedSymbols.size();
                if (ordinal >= SYMBOL_PALETTE.length()) {
                    throw new IllegalStateException("配方材料种类超过符号表容量 " + SYMBOL_PALETTE.length());
                }
                symbol = SYMBOL_PALETTE.charAt(ordinal);
                assignedSymbols.put(ingredient, symbol);
                key.put(symbol, ingredient);
            }
            lines[row].append(symbol.charValue());
        }
        return ShapedRecipePattern.of(key,
                List.of(lines[0].toString(), lines[1].toString(), lines[2].toString()));
    }
}