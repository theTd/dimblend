package dimblend.experience.compat.dirtywater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.dirtywater.BasinDirtyWaterIntent.Intent;
import dimblend.experience.compat.dirtywater.BasinDirtyWaterIntent.ItemKind;
import org.junit.jupiter.api.Test;

/**
 * 工作盆脏水意图的纯函数单测。
 *
 * <p>用户拍板的 {@code purity0_basin_default_block}：空盆（无过滤槽、无物品）默认拒脏水，
 * 让管路在输出端堵塞；药水酿造与非食物工业配方显式放行。
 */
class BasinDirtyWaterIntentTest {

    @Test
    void emptyUnfilteredBasinBlocksDirtyWater() {
        Intent intent = BasinDirtyWaterIntent.resolve(true, ItemKind.OTHER, true, false, false);
        assertSame(Intent.EMPTY, intent);
        assertFalse(BasinDirtyWaterIntent.allowsDirtyWater(intent));
    }

    @Test
    void unfilteredBasinWithPotionAllowsDirtyWater() {
        Intent intent = BasinDirtyWaterIntent.resolve(true, ItemKind.OTHER, false, true, false);
        assertSame(Intent.POTION_BREWING, intent);
        assertTrue(BasinDirtyWaterIntent.allowsDirtyWater(intent));
    }

    @Test
    void unfilteredBasinWithFoodBlocksDirtyWater() {
        Intent intent = BasinDirtyWaterIntent.resolve(true, ItemKind.OTHER, false, false, true);
        assertSame(Intent.FOOD_PREP, intent);
        assertFalse(BasinDirtyWaterIntent.allowsDirtyWater(intent));
    }

    @Test
    void unfilteredBasinWithIndustrialItemsAllowsDirtyWater() {
        Intent intent = BasinDirtyWaterIntent.resolve(true, ItemKind.OTHER, false, false, false);
        assertSame(Intent.INDUSTRIAL, intent);
        assertTrue(BasinDirtyWaterIntent.allowsDirtyWater(intent));
    }

    @Test
    void combinedPotionAndFoodPrefersPotionBrewing() {
        Intent intent = BasinDirtyWaterIntent.resolve(true, ItemKind.OTHER, false, true, true);
        assertSame(Intent.POTION_BREWING, intent);
        assertTrue(BasinDirtyWaterIntent.allowsDirtyWater(intent));
    }

    @Test
    void filterOverridesBasinContents() {
        assertSame(Intent.POTION_BREWING,
                BasinDirtyWaterIntent.resolve(false, ItemKind.POTION, false, false, true));
        assertSame(Intent.FOOD_PREP,
                BasinDirtyWaterIntent.resolve(false, ItemKind.FOOD, false, true, false));
        assertSame(Intent.INDUSTRIAL,
                BasinDirtyWaterIntent.resolve(false, ItemKind.OTHER, true, false, false));

        assertTrue(BasinDirtyWaterIntent.allowsDirtyWater(Intent.POTION_BREWING));
        assertTrue(BasinDirtyWaterIntent.allowsDirtyWater(Intent.INDUSTRIAL));
        assertFalse(BasinDirtyWaterIntent.allowsDirtyWater(Intent.FOOD_PREP));
    }
}
