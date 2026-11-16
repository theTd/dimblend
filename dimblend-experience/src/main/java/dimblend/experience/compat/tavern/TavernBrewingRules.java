package dimblend.experience.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.container.BarrelRecipeContainer;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.BarrelRecipe;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

/**
 * Kaleidoscope Tavern barrel economy: the vanilla 16-item yield becomes a 4-item yield.
 *
 * <p>Locked mode ({@link EconomyMode#KEEP_16_YIELD_QUARTER}, 2026-11-16 batch decision):
 * keeps the 16-item barrel slot limit and 16-item recipe-viewer input display while dividing
 * stackable ingredient yields by 4 (capped at 4 drinks; unmatched Vinegar fallback also 4).
 * When a valid recipe is loaded with fewer than 4 of a stackable ingredient, fermentation holds
 * idle instead of spoiling the 4 fluid buckets into Vinegar. {@link EconomyMode#CAP_4_KEEP_1TO1}
 * remains available as the alternative 1:1 mode.</p>
 */
public final class TavernBrewingRules {

    public enum EconomyMode {
        CAP_4_KEEP_1TO1,
        KEEP_16_YIELD_QUARTER
    }

    public static volatile EconomyMode ACTIVE_MODE = EconomyMode.KEEP_16_YIELD_QUARTER;

    /** Final output cap shared by both modes. */
    public static final int TARGET_OUTPUT_COUNT = 4;

    /** Vanilla barrel slot limit, kept by {@link EconomyMode#KEEP_16_YIELD_QUARTER}. */
    public static final int VANILLA_SLOT_LIMIT = 16;

    public static int slotLimit() {
        return ACTIVE_MODE == EconomyMode.CAP_4_KEEP_1TO1 ? TARGET_OUTPUT_COUNT : VANILLA_SLOT_LIMIT;
    }

    public static int viewerInputCount() {
        return slotLimit();
    }

    public static int outputCount() {
        return TARGET_OUTPUT_COUNT;
    }

    /**
     * Quarter-yield mode requires every stackable ingredient to be supplied in batches of at
     * least four before fermentation starts. Unstackable ingredients ({@code maxStackSize < 4})
     * are exempt because four of them can never occupy one slot.
     */
    public static boolean canMatchIngredients(List<ItemStack> items) {
        if (ACTIVE_MODE == EconomyMode.CAP_4_KEEP_1TO1) {
            return true;
        }
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getMaxStackSize() >= TARGET_OUTPUT_COUNT && stack.getCount() < TARGET_OUTPUT_COUNT) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when the closed barrel matches a real {@link BarrelRecipe} by item type
     * and fluid, yet has fewer than 4 of at least one stackable ingredient in quarter-yield mode.
     * Holding here prevents {@code BarrelBlockEntity#tick} from falling into the unmatched Vinegar
     * branch and consuming all 4 fluid buckets prematurely, while still letting genuinely unmatched
     * ingredients brew 4 Vinegar as intended.
     */
    public static boolean shouldHoldIncompleteRecipeBatch(
            BarrelBlockEntity barrel,
            RecipeManager.CachedCheck<BarrelRecipeContainer, BarrelRecipe> quickCheck,
            Level level) {
        if (ACTIVE_MODE == EconomyMode.CAP_4_KEEP_1TO1) {
            return false;
        }
        BarrelRecipeContainer container = new BarrelRecipeContainer(barrel.getIngredient(), barrel.getFluid());
        if (canMatchIngredients(container.getItems())) {
            return false;
        }
        return quickCheck.getRecipeFor(container, level).isPresent();
    }

    /**
     * Mirrors {@code BarrelRecipe#assemble}'s {@code min(limit, stackCount)} loop with the
     * configured economy applied.
     */
    public static int computeAssembledCount(List<ItemStack> items) {
        int[] counts = new int[items.size()];
        int[] maxStackSizes = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            counts[i] = stack.getCount();
            maxStackSizes[i] = stack.getMaxStackSize();
        }
        return computeAssembledCountFromCounts(counts, maxStackSizes, ACTIVE_MODE);
    }

    /** Pure branch-equivalent of {@link #computeAssembledCount(List)} (no ItemStack needed). */
    public static int computeAssembledCountFromCounts(int[] counts, int[] maxStackSizes, EconomyMode mode) {
        int result = TARGET_OUTPUT_COUNT;
        boolean anyIngredient = false;
        for (int i = 0; i < counts.length; i++) {
            int count = counts[i];
            if (count <= 0) {
                continue;
            }
            anyIngredient = true;
            int contribution;
            if (mode == EconomyMode.CAP_4_KEEP_1TO1) {
                contribution = count;
            } else {
                int maxStackSize = i < maxStackSizes.length ? maxStackSizes[i] : 0;
                contribution = maxStackSize < TARGET_OUTPUT_COUNT ? count : count / TARGET_OUTPUT_COUNT;
            }
            result = Math.min(result, contribution);
        }
        return anyIngredient ? Math.max(1, result) : TARGET_OUTPUT_COUNT;
    }

    private TavernBrewingRules() {
    }
}
