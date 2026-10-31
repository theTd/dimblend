package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 分液池「下界太热」附加规则的纯函数口径（见 {@link ItemDrainNetherHeat}）。 */
class ItemDrainNetherHeatTest {

    @Test
    void coversIs2r1SquareOnDrainLayerAndTheLayerAbove() {
        // 默认 r=3：覆盖 7x7，层高取分液池自身 y 与其上一层（与 N2 催熟的 y/y+1 扫描同口径）
        assertTrue(ItemDrainNetherHeat.covers(100, 64, -40, 3, 100, 64, -40), "池子自身格");
        assertTrue(ItemDrainNetherHeat.covers(100, 64, -40, 3, 103, 64, -37), "同层四角边界（含）");
        assertTrue(ItemDrainNetherHeat.covers(100, 64, -40, 3, 97, 65, -43), "上一层同角");
        assertTrue(ItemDrainNetherHeat.covers(100, 64, -40, 3, 100, 65, -40), "正上方一层（作物所在格）");
        assertFalse(ItemDrainNetherHeat.covers(100, 64, -40, 3, 104, 64, -40), "X 超出一格");
        assertFalse(ItemDrainNetherHeat.covers(100, 64, -40, 3, 103, 64, -36), "Z 超出一格");
        assertFalse(ItemDrainNetherHeat.covers(100, 64, -40, 3, 100, 66, -40), "上两层不算覆盖");
        assertFalse(ItemDrainNetherHeat.covers(100, 64, -40, 3, 100, 63, -40), "下层不算覆盖");
    }

    @Test
    void coversHandlesNegativeCoordinatesAndRadiusBounds() {
        assertTrue(ItemDrainNetherHeat.covers(-10, 70, -20, 1, -9, 70, -19));
        assertFalse(ItemDrainNetherHeat.covers(-10, 70, -20, 1, -8, 70, -20));
        assertFalse(ItemDrainNetherHeat.covers(-10, 70, -20, 1, -10, 70, -18));
        // 半径下限 0 / 上限 8（Config 允许范围）
        assertTrue(ItemDrainNetherHeat.covers(0, 0, 0, 0, 0, 0, 0));
        assertFalse(ItemDrainNetherHeat.covers(0, 0, 0, 0, 1, 0, 0));
        assertTrue(ItemDrainNetherHeat.covers(0, 0, 0, 8, 8, 0, -8));
        assertFalse(ItemDrainNetherHeat.covers(0, 0, 0, 8, 9, 0, -8));
    }

    @Test
    void coversFootprintMatchesIrrigationCoverageCells() {
        // 与 N1/N2 共用同一覆盖口径：同层格子集合必须与 IrrigationMath.coverageCells 完全一致
        int[] cells = IrrigationMath.coverageCells(100, 64, -40, 3);
        for (int x = cells[0] - 1; x <= cells[3] + 1; x++) {
            for (int z = cells[2] - 1; z <= cells[4] + 1; z++) {
                boolean inCells = x >= cells[0] && x <= cells[3] && z >= cells[2] && z <= cells[4];
                assertEquals(inCells, ItemDrainNetherHeat.covers(100, 64, -40, 3, x, 64, z),
                        "同层格 (" + x + "," + z + ")");
                assertTrue(ItemDrainNetherHeat.covers(100, 64, -40, 3, x, 65, z) == inCells,
                        "上一层格 (" + x + ",65," + z + ")");
            }
        }
    }

    @Test
    void growthIsSuppressedOnlyWhenAllThreeHold() {
        // ② 自然生长封禁 = 下界 且 在覆盖内 且 属于作物族（作物/茎/下界疣）
        assertTrue(ItemDrainNetherHeat.suppressesGrowth(true, true, true));
        assertFalse(ItemDrainNetherHeat.suppressesGrowth(false, true, true), "主世界照常生长");
        assertFalse(ItemDrainNetherHeat.suppressesGrowth(true, false, true), "覆盖半径外照常生长");
        assertFalse(ItemDrainNetherHeat.suppressesGrowth(true, true, false), "非作物族（仙人掌/甘蔗等）不拦");
    }

    @Test
    void plantingIsSuppressedOnlyWhenAllThreeHold() {
        // ③ 种植封禁 = 下界 且 在覆盖内 且 目标是可承载植物的土壤
        assertTrue(ItemDrainNetherHeat.suppressesPlanting(true, true, true));
        assertFalse(ItemDrainNetherHeat.suppressesPlanting(false, true, true), "主世界照常种植");
        assertFalse(ItemDrainNetherHeat.suppressesPlanting(true, false, true), "覆盖半径外照常种植");
        assertFalse(ItemDrainNetherHeat.suppressesPlanting(true, true, false), "非土壤方块（如池壁）不拦");
    }

    @Test
    void pipeIntakeNeedsSwitchOnAndNotNether() {
        // ④ 管道注水：开关开 且 不在下界才放开；下界一律维持原版禁止（与 ③ 的桶右键无关）
        assertTrue(ItemDrainNetherHeat.pipeIntakeAllowed(true, false), "主世界 + 开 → 允许");
        assertFalse(ItemDrainNetherHeat.pipeIntakeAllowed(true, true), "下界 + 开 → 仍禁止");
        assertFalse(ItemDrainNetherHeat.pipeIntakeAllowed(false, false), "关开关 → 禁止");
        assertFalse(ItemDrainNetherHeat.pipeIntakeAllowed(false, true), "关开关 + 下界 → 禁止");
    }

    @Test
    void growthBoostRunsOnlyOutsideNether() {
        // ② 的 N2 侧：下界内不做骨粉也不再抽水；下界外维持原逻辑
        assertTrue(ItemDrainNetherHeat.growthBoostAllowed(false));
        assertFalse(ItemDrainNetherHeat.growthBoostAllowed(true));
    }
}