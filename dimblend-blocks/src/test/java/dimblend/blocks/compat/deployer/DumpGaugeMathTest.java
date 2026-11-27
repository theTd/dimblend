package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 卸料仪表滑条映射（锁定判定 #19）、单包上限与检查相位的边界自检。 */
class DumpGaugeMathTest {

    @Test
    void rawZeroMeansDisabled() {
        assertTrue(DumpGaugeMath.isInactive(0));
        assertFalse(DumpGaugeMath.isInactive(1), "raw 1 是目标 0，不是禁用");
        assertEquals(DumpGaugeMath.SIGNAL_INACTIVE, DumpGaugeMath.targetUnits(0));
        assertEquals(-1L, DumpGaugeMath.targetItems(0, false, 64));
        assertEquals(0, DumpGaugeMath.dumpAmountForUnits(DumpGaugeMath.targetUnits(0), false, 64, 1000),
                "未启用时一个都不发");
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
        // 目标 0：只要库存 > 0 就能发，单轮上限一个包裹
        assertEquals(DumpGaugeMath.MAX_ITEMS_PER_PACKAGE, DumpGaugeMath.dumpAmountForUnits(0, true, 64, 1000));
        // 库存小于一批时发库存全部
        assertEquals(10, DumpGaugeMath.dumpAmountForUnits(0, false, 64, 10));
        assertEquals(0, DumpGaugeMath.dumpAmountForUnits(0, false, 64, 0));
    }

    @Test
    void dumpStopsExactlyAtTarget() {
        // 目标 1 组铁锭 = 64；库存 100 -> 发 36，正好停住
        assertEquals(36, DumpGaugeMath.dumpAmountForUnits(1, true, 64, 100));
        // 库存刚好等于目标 -> 不发
        assertEquals(0, DumpGaugeMath.dumpAmountForUnits(1, true, 64, 64));
        // 库存低于目标 -> 不发（绝不反向补货）
        assertEquals(0, DumpGaugeMath.dumpAmountForUnits(1, true, 64, 63));
        assertEquals(0, DumpGaugeMath.dumpAmountForUnits(49, true, 64, 100));
    }

    @Test
    void batchIsCappedAtOnePackage() {
        // 目标 0，库存极大 -> 单轮只发一个包裹（64 个），而不是 9 组 576 个
        assertEquals(64, DumpGaugeMath.dumpAmountForUnits(0, true, 64, Integer.MAX_VALUE / 4));
        assertEquals(64, DumpGaugeMath.dumpAmountForUnits(0, true, 16, 10_000), "16 个一组的物品也是 64/包");
        // 目标 1 组，库存 9 组：差额 512 被夹成 64
        assertEquals(64, DumpGaugeMath.dumpAmountForUnits(1, true, 64, 9 * 64));
    }

    @Test
    void itemsPerPackageFollowsThePackagerRule() {
        // Create 打包机按 min(64, count) 抽取 -> 散装物 64；单格上限更小时按 组数 走
        assertEquals(DumpGaugeMath.MAX_ITEMS_PER_PACKAGE, DumpGaugeMath.itemsPerPackage(64, true));
        assertEquals(DumpGaugeMath.MAX_ITEMS_PER_PACKAGE, DumpGaugeMath.itemsPerPackage(16, true));
        assertEquals(DumpGaugeMath.MAX_BATCH_STACKS, DumpGaugeMath.itemsPerPackage(1, true));
        assertEquals(DumpGaugeMath.MAX_BATCH_STACKS, DumpGaugeMath.itemsPerPackage(0, true), "非法上限按 1 处理");
        // 装不进容器的大件（潜影盒之类）抽一件就 break
        assertEquals(1, DumpGaugeMath.itemsPerPackage(1, false));
        assertEquals(1, DumpGaugeMath.itemsPerPackage(64, false));
    }

    @Test
    void illegalMaxStackSizeFallsBackToOne() {
        assertEquals(9, DumpGaugeMath.dumpAmountForUnits(0, true, 0, 100), "maxStackSize<=0 一律按 1 处理");
        assertEquals(99L, DumpGaugeMath.targetItems(100, true, 0));
    }

    @Test
    void storageDrainsInPackageSizedCyclesUntilItReachesTheTarget() {
        int maxStack = 64;
        int perPackage = DumpGaugeMath.itemsPerPackage(maxStack, true);
        int storage = 200;

        // 目标 1 组 = 64：64 -> 64 -> 8 -> 停
        assertEquals(64, DumpGaugeMath.dumpAmountForUnits(1, true, maxStack, storage, perPackage));
        storage -= 64; // 136
        assertEquals(64, DumpGaugeMath.dumpAmountForUnits(1, true, maxStack, storage, perPackage));
        storage -= 64; // 72
        assertEquals(8, DumpGaugeMath.dumpAmountForUnits(1, true, maxStack, storage, perPackage));
        storage -= 8; // 64
        assertEquals(0, DumpGaugeMath.dumpAmountForUnits(1, true, maxStack, storage, perPackage),
                "停在目标值上，不再继续卸");
    }

    @Test
    void targetZeroDrainsEverythingInBoundedCycles() {
        int storage = 200;
        int dumps = 0;
        while (true) {
            int amount = DumpGaugeMath.dumpAmountForUnits(0, true, 64, storage);
            if (amount == 0) {
                break;
            }
            assertTrue(amount <= DumpGaugeMath.MAX_ITEMS_PER_PACKAGE);
            storage -= amount;
            dumps++;
        }
        assertEquals(0, storage);
        assertEquals(4, dumps, "200 个 / 每轮 64 = 4 轮");
    }

    @Test
    void unstackableItemsDrainNinePerCycleAtMost() {
        int perPackage = DumpGaugeMath.itemsPerPackage(1, true);
        assertEquals(9, perPackage);
        int storage = 30;
        int dumps = 0;
        while (true) {
            int amount = DumpGaugeMath.dumpAmountForUnits(0, true, 1, storage, perPackage);
            if (amount == 0) {
                break;
            }
            assertTrue(amount <= 9, "单格上限 1 的物品每轮最多 9 个");
            storage -= amount;
            dumps++;
        }
        assertEquals(0, storage);
        assertEquals(4, dumps, "30 个 / 每轮 9 = 4 轮");
    }

    @Test
    void bulkyItemsGoOnePerPackage() {
        int perPackage = DumpGaugeMath.itemsPerPackage(64, false);
        assertEquals(1, perPackage);
        int storage = 10;
        int dumps = 0;
        while (true) {
            int amount = DumpGaugeMath.dumpAmountForUnits(0, true, 64, storage, perPackage);
            if (amount == 0) {
                break;
            }
            assertEquals(1, amount, "大件一件一包");
            storage -= amount;
            dumps++;
        }
        assertEquals(10, dumps);
    }

    @Test
    void checkTickIsStaggeredPerPositionAndHitsOncePerInterval() {
        // 哈希 0 的面板在 gameTime 0/80/160 检查
        assertTrue(DumpGaugeMath.isCheckTick(0, 0, DumpGaugeMath.CHECK_INTERVAL_TICKS));
        assertTrue(DumpGaugeMath.isCheckTick(80, 0, DumpGaugeMath.CHECK_INTERVAL_TICKS));
        assertFalse(DumpGaugeMath.isCheckTick(79, 0, DumpGaugeMath.CHECK_INTERVAL_TICKS));

        // 一个周期内每个面板恰好命中一次
        int hits = 0;
        for (long time = 0; time < DumpGaugeMath.CHECK_INTERVAL_TICKS; time++) {
            if (DumpGaugeMath.isCheckTick(time, 12345, DumpGaugeMath.CHECK_INTERVAL_TICKS)) {
                hits++;
            }
        }
        assertEquals(1, hits);

        // 位置不同 -> 相位不同（错峰），负哈希也不能整轮错过
        assertFalse(DumpGaugeMath.isCheckTick(0, 12345, DumpGaugeMath.CHECK_INTERVAL_TICKS));
        assertTrue(DumpGaugeMath.isCheckTick(1, -1, DumpGaugeMath.CHECK_INTERVAL_TICKS));
        assertFalse(DumpGaugeMath.isCheckTick(0, -1, DumpGaugeMath.CHECK_INTERVAL_TICKS));
    }

    @Test
    void bulbIsDisabledWithoutTargetAndTurnsRedWhenPaused() {
        assertEquals(DumpGaugeMath.Bulb.DISABLED, DumpGaugeMath.bulbState(0, false));
        assertEquals(DumpGaugeMath.Bulb.DISABLED, DumpGaugeMath.bulbState(0, true),
                "目标 0 时面板模型没有棱柱，供电也不该画出玻璃珠");
        assertEquals(DumpGaugeMath.Bulb.GREEN, DumpGaugeMath.bulbState(1, false));
        assertEquals(DumpGaugeMath.Bulb.GREEN, DumpGaugeMath.bulbState(50, false));
        assertEquals(DumpGaugeMath.Bulb.RED, DumpGaugeMath.bulbState(50, true));
    }

    @Test
    void redstonePowerPausesDispatch() {
        assertTrue(DumpGaugeMath.mayDispatch(true, false, "factory/main"));
        assertFalse(DumpGaugeMath.mayDispatch(true, true, "factory/main"), "红石供电 = 暂停卸料");
        assertFalse(DumpGaugeMath.mayDispatch(false, false, "factory/main"), "没目标不发");
        assertFalse(DumpGaugeMath.mayDispatch(true, false, ""), "没地址不发");
        assertFalse(DumpGaugeMath.mayDispatch(true, false, "   "), "空白地址不发");
        assertFalse(DumpGaugeMath.mayDispatch(true, false, null), "输入未加载时地址为 null");
    }

    @Test
    void periodIsShownInSeconds() {
        assertEquals("4", DumpGaugeMath.secondsText(DumpGaugeMath.CHECK_INTERVAL_TICKS));
        assertEquals("0.5", DumpGaugeMath.secondsText(10));
        assertEquals("4.5", DumpGaugeMath.secondsText(90));
        assertEquals("0", DumpGaugeMath.secondsText(0));
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
        assertEquals(64, DumpGaugeMath.MAX_ITEMS_PER_PACKAGE);
        assertEquals(80, DumpGaugeMath.CHECK_INTERVAL_TICKS);
    }
}
