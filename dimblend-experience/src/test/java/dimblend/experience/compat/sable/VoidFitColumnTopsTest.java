package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/** G6 柱顶索引（见 {@link VoidFitColumnTops}）：按 (x,z) 柱取最高目标格 y，驱动熔岩保留口径。 */
class VoidFitColumnTopsTest {

    private static long cell(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    @Test
    void picksHighestYPerColumn() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(1, 5, 2));
        cells.add(cell(1, 9, 2));
        cells.add(cell(1, 3, 2));
        cells.add(cell(1, 7, 3)); // 另一柱：互不影响
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(cells);
        assertEquals(9, tops.get(VoidFitColumnTops.columnKey(1, 2)));
        assertEquals(7, tops.get(VoidFitColumnTops.columnKey(1, 3)));
    }

    @Test
    void negativeCoordinatesAndNegativeY() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(-10, -60, 4));
        cells.add(cell(-10, 100, 4));
        cells.add(cell(10, 3, 4));   // x 符号不同
        cells.add(cell(-10, 8, -4)); // z 符号不同
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(cells);
        assertEquals(100, tops.get(VoidFitColumnTops.columnKey(-10, 4)));
        assertEquals(3, tops.get(VoidFitColumnTops.columnKey(10, 4)));
        assertEquals(8, tops.get(VoidFitColumnTops.columnKey(-10, -4)));
    }

    @Test
    void isTopOnlyAtColumnTop() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(0, 64, 0));
        cells.add(cell(0, 65, 0));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(cells);
        assertTrue(VoidFitColumnTops.isTop(tops, cell(0, 65, 0)));
        assertFalse(VoidFitColumnTops.isTop(tops, cell(0, 64, 0)));
    }

    @Test
    void missingColumnNeverMatchesEvenAtY0() {
        // 缺柱缺省值 MIN_VALUE：y=0 的合法高度也不会误判为柱顶
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(new LongOpenHashSet());
        assertEquals(Integer.MIN_VALUE, tops.get(VoidFitColumnTops.columnKey(5, 5)));
        assertFalse(VoidFitColumnTops.isTop(tops, cell(5, 0, 5)));
    }

    @Test
    void sealedTopRequiresTargetDirectlyBelow() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(0, 64, 0));
        cells.add(cell(0, 65, 0));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(cells);
        // 两格连续柱：柱顶下方还有目标格，保留熔岩安全
        assertTrue(VoidFitColumnTops.isSealedTop(tops, cells, cell(0, 65, 0)));
        // 柱内更低格不是柱顶，永远不能保留（它是"柱顶下方的投影格"）
        assertFalse(VoidFitColumnTops.isSealedTop(tops, cells, cell(0, 64, 0)));
    }

    @Test
    void singleCellColumnIsUnsealedAndGetsSealCell() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(3, -20, 7));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(cells);
        assertFalse(VoidFitColumnTops.isSealedTop(tops, cells, cell(3, -20, 7)));
        LongOpenHashSet seals = VoidFitColumnTops.sealCellsFor(cells, -64);
        LongOpenHashSet expected = new LongOpenHashSet();
        expected.add(cell(3, -21, 7));
        assertEquals(expected, seals);
        // 封口格并入目标集后，柱顶变为安全柱顶（柱顶语义不变）
        LongOpenHashSet sealedTargets = new LongOpenHashSet(cells);
        sealedTargets.addAll(seals);
        assertTrue(VoidFitColumnTops.isSealedTop(tops, sealedTargets, cell(3, -20, 7)));
        assertTrue(VoidFitColumnTops.isTop(tops, cell(3, -20, 7)));
    }

    @Test
    void gapBelowTopIsUnsealedAndGetsSealCellAtTheGap() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(1, 60, 1));
        cells.add(cell(1, 62, 1)); // 61 悬空（外挑下沿/间隙）
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(cells);
        assertEquals(62, tops.get(VoidFitColumnTops.columnKey(1, 1)));
        assertFalse(VoidFitColumnTops.isSealedTop(tops, cells, cell(1, 62, 1)));
        LongOpenHashSet seals = VoidFitColumnTops.sealCellsFor(cells, -64);
        LongOpenHashSet expected = new LongOpenHashSet();
        expected.add(cell(1, 61, 1));
        assertEquals(expected, seals);
        // 下方 60 是柱内格而不是柱顶，本身不需要封口
        assertFalse(VoidFitColumnTops.isTop(tops, cell(1, 60, 1)));
    }

    @Test
    void sealOnlyFillsUnsealedTopsAcrossColumns() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(0, 10, 0));
        cells.add(cell(0, 11, 0)); // A：密封
        cells.add(cell(2, 5, 0));  // B：单格柱
        cells.add(cell(4, 6, 0));
        cells.add(cell(4, 8, 0));  // C：7 悬空
        LongOpenHashSet seals = VoidFitColumnTops.sealCellsFor(cells, -64);
        LongOpenHashSet expected = new LongOpenHashSet();
        expected.add(cell(2, 4, 0));
        expected.add(cell(4, 7, 0));
        assertEquals(expected, seals);
    }

    @Test
    void sealRespectsWorldFloor() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(0, -64, 0)); // 世界最低层：下方在世界外，不补
        cells.add(cell(1, -63, 0)); // 单格柱：下方 -64 可补
        LongOpenHashSet seals = VoidFitColumnTops.sealCellsFor(cells, -64);
        LongOpenHashSet expected = new LongOpenHashSet();
        expected.add(cell(1, -64, 0));
        assertEquals(expected, seals);
    }

    @Test
    void sealDoesNotMutateInputTargets() {
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(cell(0, 5, 0));
        LongOpenHashSet before = new LongOpenHashSet(cells);
        VoidFitColumnTops.sealCellsFor(cells, -64);
        assertEquals(before, cells);
    }

    @Test
    void cellBelowKeepsXZAndDecrementsY() {
        assertEquals(cell(-7, 41, 12), VoidFitColumnTops.cellBelow(cell(-7, 42, 12)));
    }
}
