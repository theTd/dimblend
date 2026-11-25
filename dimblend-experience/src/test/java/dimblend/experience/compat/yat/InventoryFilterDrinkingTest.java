package dimblend.experience.compat.yat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 背包滤芯口径（LOCKED DECISION #8 {@code filter_cost_uniform_1000}）。 */
class InventoryFilterDrinkingTest {

    @Test
    void onlyPurityZeroNeedsFiltering() {
        assertTrue(InventoryFilterDrinking.needsFilter(0));
        for (int purity = 1; purity <= 3; purity++) {
            assertFalse(InventoryFilterDrinking.needsFilter(purity), "purity " + purity + " 不动滤芯");
        }
        assertEquals(1, InventoryFilterDrinking.filteredPurity());
    }

    @Test
    void everyDrinkCostsFlatOneThousand() {
        assertEquals(1000.0, InventoryFilterDrinking.COST_PER_DRINK);
        InventoryFilterDrinking.Consumption first = InventoryFilterDrinking.afterDrink(2400.0);
        assertEquals(1400.0, first.newLifespan());
        assertFalse(first.clogged());
        InventoryFilterDrinking.Consumption second = InventoryFilterDrinking.afterDrink(first.newLifespan());
        assertEquals(400.0, second.newLifespan());
        assertFalse(second.clogged());
        InventoryFilterDrinking.Consumption third = InventoryFilterDrinking.afterDrink(second.newLifespan());
        assertEquals(-600.0, third.newLifespan());
        assertTrue(third.clogged(), "扣成负数即堵死");
    }

    @Test
    void exactlyZeroLifespanClogs() {
        assertTrue(InventoryFilterDrinking.afterDrink(1000.0).clogged());
        assertFalse(InventoryFilterDrinking.afterDrink(1000.5).clogged());
    }

    @Test
    void partiallyUsedCoreWinsOverFullOne() {
        List<InventoryFilterDrinking.CoreSlot> slots = List.of(
                new InventoryFilterDrinking.CoreSlot(0, 3000.0, 3000.0),
                new InventoryFilterDrinking.CoreSlot(5, 1200.0, 3000.0));
        assertEquals(5, InventoryFilterDrinking.chooseSlot(slots));
    }

    @Test
    void firstPartiallyUsedInScanOrderWins() {
        List<InventoryFilterDrinking.CoreSlot> slots = List.of(
                new InventoryFilterDrinking.CoreSlot(2, 2000.0, 3000.0),
                new InventoryFilterDrinking.CoreSlot(7, 900.0, 3000.0));
        assertEquals(2, InventoryFilterDrinking.chooseSlot(slots));
    }

    @Test
    void fallsBackToFirstFullInHotbarThenMain() {
        // 调用方按 0..8 再 9..35 传入，所以"先快捷栏后背包"由列表顺序保证
        List<InventoryFilterDrinking.CoreSlot> slots = List.of(
                new InventoryFilterDrinking.CoreSlot(0, 3000.0, 3000.0),
                new InventoryFilterDrinking.CoreSlot(3, 3000.0, 3000.0),
                new InventoryFilterDrinking.CoreSlot(20, 3000.0, 3000.0));
        assertEquals(0, InventoryFilterDrinking.chooseSlot(slots));
        assertEquals(3, InventoryFilterDrinking.chooseSlot(slots.subList(1, 3)));
    }

    @Test
    void noCoreMeansNoFiltering() {
        assertTrue(InventoryFilterDrinking.chooseSlot(List.of()) < 0);
        List<InventoryFilterDrinking.CoreSlot> dead = List.of(
                new InventoryFilterDrinking.CoreSlot(1, 0.0, 3000.0),
                new InventoryFilterDrinking.CoreSlot(2, -50.0, 3000.0));
        assertTrue(InventoryFilterDrinking.chooseSlot(dead) < 0, "寿命非正/负数的坏数据不参与");
    }

    @Test
    void slotBoundsMatchVanillaInventory() {
        assertEquals(9, InventoryFilterDrinking.HOTBAR_SLOTS);
        assertEquals(36, InventoryFilterDrinking.MAIN_SLOTS);
    }
}
