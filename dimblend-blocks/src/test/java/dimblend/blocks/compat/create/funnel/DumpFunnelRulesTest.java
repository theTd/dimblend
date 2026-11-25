package dimblend.blocks.compat.create.funnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

/**
 * 卸料漏斗纯判定自检：按物品（{@code Item + DataComponents}）汇总的计数、1..64 组阈值、
 * 64/16/1 三种 maxStackSize 的换算、夹取与"只抽 1 个"。
 */
class DumpFunnelRulesTest {

    private record FakeStack(String itemId, String componentsTag, int count) {
        static final FakeStack EMPTY = new FakeStack("", "", 0);

        boolean isEmpty() {
            return count <= 0 || itemId.isEmpty();
        }

        boolean sameItemSameComponents(FakeStack other) {
            return Objects.equals(this.itemId, other.itemId)
                    && Objects.equals(this.componentsTag, other.componentsTag);
        }

        FakeStack single() {
            return new FakeStack(itemId, componentsTag, 1);
        }
    }

    private static List<DumpFunnelRules.BucketCount<FakeStack>> aggregate(List<FakeStack> stacks) {
        return DumpFunnelRules.aggregateBuckets(
                stacks,
                FakeStack::isEmpty,
                FakeStack::sameItemSameComponents,
                FakeStack::count,
                FakeStack::single);
    }

    private static long totalOf(List<DumpFunnelRules.BucketCount<FakeStack>> totals, FakeStack candidate) {
        return DumpFunnelRules.totalOfBucket(
                totals,
                candidate,
                FakeStack::isEmpty,
                FakeStack::sameItemSameComponents);
    }

    @Test
    void stacksAreClampedToOneThroughSixtyFour() {
        assertEquals(1, DumpFunnelRules.clampStacks(0), "0 组不被允许：无条件抽干容器");
        assertEquals(1, DumpFunnelRules.clampStacks(-7));
        assertEquals(1, DumpFunnelRules.clampStacks(1));
        assertEquals(16, DumpFunnelRules.clampStacks(16));
        assertEquals(64, DumpFunnelRules.clampStacks(64));
        assertEquals(64, DumpFunnelRules.clampStacks(200));
    }

    @Test
    void thresholdItemsScaleByEachItemsOwnMaxStackSize() {
        assertEquals(64L, DumpFunnelRules.thresholdItems(1, 64));
        assertEquals(4096L, DumpFunnelRules.thresholdItems(64, 64));
        assertEquals(16L, DumpFunnelRules.thresholdItems(1, 16));
        assertEquals(1024L, DumpFunnelRules.thresholdItems(64, 16));
        assertEquals(1L, DumpFunnelRules.thresholdItems(1, 1));
        assertEquals(64L, DumpFunnelRules.thresholdItems(64, 1));
        // 非法 maxStackSize 收敛到 1，绝不返回 0（否则等于"永不下料/全下料"的边界歧义）
        assertEquals(5L, DumpFunnelRules.thresholdItems(5, 0));
        // 夹取生效：0 组按 1 组算
        assertEquals(16L, DumpFunnelRules.thresholdItems(0, 16));
    }

    @Test
    void dumpsOnlyStrictlyAboveThreshold() {
        // 1 组铁锭 = 64 个：恰好 64 不该卸，65 才卸
        assertFalse(DumpFunnelRules.shouldDump(64L, 1, 64), "等于阈值必须停手");
        assertTrue(DumpFunnelRules.shouldDump(65L, 1, 64));
        assertFalse(DumpFunnelRules.shouldDump(0L, 1, 64));
        // 2 组雪球 = 32 个
        assertFalse(DumpFunnelRules.shouldDump(32L, 2, 16));
        assertTrue(DumpFunnelRules.shouldDump(33L, 2, 16));
        // 1 组不可堆叠物 = 1 个
        assertFalse(DumpFunnelRules.shouldDump(1L, 1, 1));
        assertTrue(DumpFunnelRules.shouldDump(2L, 1, 1));
    }

    @Test
    void countsArePerDistinctItemNotPerSlotNorContainerWide() {
        List<FakeStack> container = List.of(
                new FakeStack("minecraft:iron_ingot", "", 30),
                new FakeStack("minecraft:iron_ingot", "", 40),
                new FakeStack("minecraft:gold_ingot", "", 70));

        List<DumpFunnelRules.BucketCount<FakeStack>> totals = aggregate(container);

        assertEquals(2, totals.size(), "两种不同物品 -> 两条汇总");
        assertEquals(70L, totalOf(totals, new FakeStack("minecraft:iron_ingot", "", 1)), "同物品跨槽相加");
        assertEquals(70L, totalOf(totals, new FakeStack("minecraft:gold_ingot", "", 1)));

        // 铁锭 70 > 1 组(64) -> 卸；金锭也是 70 -> 卸；但两者互不影响
        assertTrue(DumpFunnelRules.shouldDump(
                totalOf(totals, new FakeStack("minecraft:iron_ingot", "", 1)), 1, 64));
        // 换成 1 组雪球阈值：金锭不参与雪球判定
        assertEquals(0L, totalOf(totals, new FakeStack("minecraft:snowball", "", 1)));
    }

    @Test
    void componentsSplitTheSameItemIntoSeparateBuckets() {
        FakeStack plain = new FakeStack("minecraft:diamond_sword", "", 3);
        FakeStack named = new FakeStack("minecraft:diamond_sword", "custom_name=卸料样品", 5);

        List<DumpFunnelRules.BucketCount<FakeStack>> totals = aggregate(List.of(plain, named));

        assertEquals(2, totals.size(), "DataComponents 不同必须分成两条");
        assertEquals(3L, totalOf(totals, new FakeStack("minecraft:diamond_sword", "", 1)));
        assertEquals(5L, totalOf(totals, new FakeStack("minecraft:diamond_sword", "custom_name=卸料样品", 1)));
    }

    @Test
    void emptyStacksAreIgnored() {
        List<DumpFunnelRules.BucketCount<FakeStack>> totals = aggregate(
                List.of(FakeStack.EMPTY, new FakeStack("minecraft:snowball", "", 4), FakeStack.EMPTY));
        assertEquals(1, totals.size());
        assertEquals(4L, totalOf(totals, new FakeStack("minecraft:snowball", "", 1)));
        assertEquals(0L, totalOf(totals, FakeStack.EMPTY));
    }

    @Test
    void lockedConstantsHoldShape() {
        assertEquals(1, DumpFunnelRules.MIN_STACKS);
        assertEquals(64, DumpFunnelRules.MAX_STACKS);
        assertEquals(1, DumpFunnelRules.DEFAULT_STACKS);
        assertEquals(16, DumpFunnelRules.MILESTONE_INTERVAL);
        assertEquals(1, DumpFunnelRules.EXTRACT_AMOUNT, "触发时永远只抽 1 个");
    }
}
