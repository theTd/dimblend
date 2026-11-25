package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 卸料仪表滑条映射（锁定判定 #19）的边界自检。 */
class DumpGaugeMathTest {

    @Test
    void rawZeroMeansDisabled() {
        assertTrue(DumpGaugeMath.isInactive(0));
        assertFalse(DumpGaugeMath.isInactive(1), "raw 1 是目标 0，不是禁用");
        assertEquals(DumpGaugeMath.SIGNAL_INACTIVE, DumpGaugeMath.targetUnits(0));
        assertEquals(-1L, DumpGaugeMath.targetItems(0, false, 64));
        assertEquals(0, DumpGaugeMath.dumpAmount(0, false, 64, 1000), "未启用时一个都不发");
        assertNull(DumpGaugeMath.displayUnits(0));
    }

    @Test
    void rawIsShiftedByOneForNumbersAndStacks() {
        // items 行：units == raw - 1
        assertEquals(0, DumpGaugeMath.targetUnits(1));
        assertEquals(1, DumpGaugeMath.targetUnits(2));
        assertEquals(99, DumpGaugeMath.targetUnits(100));

        assertEquals(0L, DumpGaugeMath.targetItems(1, false, 64));
        assertEquals(1L, DumpGaugeMath.targetItems(2, false, 64));
        assertEquals(99L, DumpGaugeMath.targetItems(100, false, 64));

        // stacks 行：units * maxStackSize
        assertEquals(0L, DumpGaugeMath.targetItems(1, true, 64));
        assertEquals(64L, DumpGaugeMath.targetItems(2, true, 64));
        assertEquals(99L * 64L, DumpGaugeMath.targetItems(100, true, 64));
        assertEquals(99L * 16L, DumpGaugeMath.targetItems(100, true, 16));
        assertEquals(99L, DumpGaugeMath.targetItems(100, true, 1));
    }

    @Test
    void rawOneEmptiesTheStorage() {
        // 目标 0：只要库存 > 0 就能发，单次上限 9 组
        assertEquals(64 * 9, DumpGaugeMath.dumpAmount(1, true, 64, 1000));
        // 库存小于一批时发库存全部
        assertEquals(10, DumpGaugeMath.dumpAmount(1, false, 64, 10));
        assertEquals(0, DumpGaugeMath.dumpAmount(1, false, 64, 0));
    }

    @Test
    void dumpStopsExactlyAtTarget() {
        // 目标 1 组铁锭 = 64；库存 100 -> 发 36，正好停住
        assertEquals(36, DumpGaugeMath.dumpAmount(2, true, 64, 100));
        // 库存刚好等于目标 -> 不发
        assertEquals(0, DumpGaugeMath.dumpAmount(2, true, 64, 64));
        // 库存低于目标 -> 不发（绝不反向补货）
        assertEquals(0, DumpGaugeMath.dumpAmount(2, true, 64, 63));
        assertEquals(0, DumpGaugeMath.dumpAmount(50, true, 64, 100));
    }

    @Test
    void batchIsCappedAtNineStacks() {
        // 目标 0，库存极大 -> 单次只发 9 组
        assertEquals(64 * 9, DumpGaugeMath.dumpAmount(1, true, 64, Integer.MAX_VALUE / 4));
        assertEquals(16 * 9, DumpGaugeMath.dumpAmount(1, true, 16, 10_000));
        // 目标 1 组，库存 1 组 + 8 组些微多 -> 差额小于上限
        assertEquals(8 * 64, DumpGaugeMath.dumpAmount(2, true, 64, 9 * 64));
    }

    @Test
    void illegalMaxStackSizeFallsBackToOne() {
        assertEquals(9, DumpGaugeMath.dumpAmount(1, true, 0, 100), "maxStackSize<=0 一律按 1 处理");
        assertEquals(99L, DumpGaugeMath.targetItems(100, true, 0));
    }

    @Test
    void externalSignalMapsDirectlyToUnits() {
        assertEquals(DumpGaugeMath.SIGNAL_INACTIVE, DumpGaugeMath.unitsFromSignal(-1.0F));
        assertEquals(DumpGaugeMath.SIGNAL_INACTIVE, DumpGaugeMath.unitsFromSignal(Float.NaN));
        assertEquals(DumpGaugeMath.SIGNAL_INACTIVE, DumpGaugeMath.unitsFromSignal(Float.NEGATIVE_INFINITY));
        assertEquals(0, DumpGaugeMath.unitsFromSignal(0.0F), "信号 0 就是卸空，不是禁用");
        assertEquals(3, DumpGaugeMath.unitsFromSignal(3.7F), "向下取整");
        assertEquals(DumpGaugeMath.MAX_UNITS, DumpGaugeMath.unitsFromSignal(500.0F));
        // 信号路径进来后与滑条路径共用同一件数换算
        assertEquals(3L * 64L, DumpGaugeMath.itemsOf(DumpGaugeMath.unitsFromSignal(3.0F), true, 64));
        assertEquals(0L, DumpGaugeMath.itemsOf(DumpGaugeMath.unitsFromSignal(0.0F), true, 64));
    }

    @Test
    void clampingCoversSliderNoise() {
        assertEquals(0, DumpGaugeMath.clampRaw(-5));
        assertEquals(0, DumpGaugeMath.clampRaw(0));
        assertEquals(100, DumpGaugeMath.clampRaw(100));
        assertEquals(100, DumpGaugeMath.clampRaw(250));
        assertEquals(DumpGaugeMath.MAX_UNITS, DumpGaugeMath.displayUnits(150));
    }

    @Test
    void lockedConstantsHoldShape() {
        assertEquals(0, DumpGaugeMath.RAW_MIN);
        assertEquals(100, DumpGaugeMath.RAW_MAX);
        assertEquals(0, DumpGaugeMath.RAW_INACTIVE);
        assertEquals(-1, DumpGaugeMath.SIGNAL_INACTIVE);
        assertEquals(99, DumpGaugeMath.MAX_UNITS);
        assertEquals(9, DumpGaugeMath.MAX_BATCH_STACKS);
    }
}
