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
}
