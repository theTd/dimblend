package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * 熔穿孔封口塞判定（见 {@link VoidFitMeltSeal}）：离开投影的已物化空位，只有在
 * "正上方世界格是岩浆 + 孔口比一格深 + 孔口下方仍有本车目标格" 时保成 structure_void。
 *
 * <p>三条边界正是 2026-10-31 实机缺陷的修法边界：封（否则整根岩浆柱切进车厢）、
 * 不封一格深的实心堆叠（否则熔岩逐层下降停住）、不封整柱搬走的移动残影（目标集残留）。</p>
 */
class VoidFitMeltSealTest {

    private static long cell(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private static LongOpenHashSet cells(long... values) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (long value : values) {
            set.add(value);
        }
        return set;
    }

    /** 空心车厢：孔口 y=6 下方是舱内空气，更下方 y=2/3 是地板投影仍在本轮 target 内。 */
    @Test
    void deepHoleAboveRemainingShellMustBeSealed() {
        LongOpenHashSet target = cells(cell(0, 2, 0), cell(0, 3, 0));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(target);
        assertTrue(VoidFitMeltSeal.isHoleMouth(target, cell(0, 6, 0)),
                "cell below the hole mouth is not in target: this is a hole deeper than one cell");
        assertTrue(VoidFitMeltSeal.shouldSeal(true, tops, target, cell(0, 6, 0)));
    }

    /** 实心堆叠：熔穿格正下方仍被下层方块的 +1 覆盖留在 target 内 ⇒ 照旧下降一层，不封口。 */
    @Test
    void oneLayerDeepHoleKeepsDescending() {
        LongOpenHashSet target = cells(cell(0, 4, 0));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(target);
        assertFalse(VoidFitMeltSeal.isHoleMouth(target, cell(0, 5, 0)));
        assertFalse(VoidFitMeltSeal.shouldSeal(true, tops, target, cell(0, 5, 0)),
                "solid stacks must keep descending one layer after the melted layer");
    }

    @Test
    void noLavaAboveDoesNotSeal() {
        LongOpenHashSet target = cells(cell(0, 2, 0));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(target);
        assertFalse(VoidFitMeltSeal.shouldSeal(false, tops, target, cell(0, 6, 0)),
                "a void cell under open air is a normal projection shrink, not a melt-through hole");
    }

    @Test
    void vacatedColumnWithoutShellBelowDoesNotSeal() {
        // 载具驶离后整柱不再有本车目标格：孔口下方没有壳层，保下去只会把空位留在原地
        LongOpenHashSet target = cells(cell(5, 4, 5));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(target);
        assertTrue(VoidFitMeltSeal.isHoleMouth(target, cell(0, 6, 0)));
        assertFalse(VoidFitMeltSeal.shouldSeal(true, tops, target, cell(0, 6, 0)));
    }

    @Test
    void shellBelowInAnotherColumnDoesNotCount() {
        // 只有同 (x,z) 柱的壳层才算：相邻柱地板撑不起这个孔口
        LongOpenHashSet target = cells(cell(1, 2, 0));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(target);
        assertFalse(VoidFitMeltSeal.shouldSeal(true, tops, target, cell(0, 6, 0)));
    }

    @Test
    void negativeCoordinatesKeepTheSameRule() {
        LongOpenHashSet target = cells(cell(-7, -60, 12));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(target);
        assertTrue(VoidFitMeltSeal.shouldSeal(true, tops, target, cell(-7, -40, 12)));
        assertFalse(VoidFitMeltSeal.shouldSeal(true, tops, target, cell(-7, -59, 12)),
                "below cell (-60) is still in target: one-layer step, not a hole mouth");
    }

    @Test
    void oneLayerAboveTheShellIsNotAHoleMouth() {
        LongOpenHashSet target = cells(cell(0, 2, 0));
        Long2IntOpenHashMap tops = VoidFitColumnTops.of(target);
        assertTrue(VoidFitMeltSeal.isHoleMouth(target, cell(0, 4, 0)));
        assertFalse(VoidFitMeltSeal.isHoleMouth(target, cell(0, 3, 0)));
    }
}
