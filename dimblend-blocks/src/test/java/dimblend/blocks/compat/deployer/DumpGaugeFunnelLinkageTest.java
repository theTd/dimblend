package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dimblend.blocks.compat.create.funnel.DumpFunnelRules;

/**
 * 卸料仪表 ↔ 卸料漏斗的联动词汇表：两者都"到阈值就停"，但一个管网络库存、一个管本地容器。
 *
 * <p>仪表（本包）：网络里该物品存量 <b>严格大于</b>目标 → 一次下单搬走 (存量 − 目标)，单笔上限 9 组。<br>
 * 漏斗（{@code compat.create.funnel}）：容器里该物品总数 <b>严格大于</b>阈值（N 组）→ 每次只抽
 * {@link DumpFunnelRules#EXTRACT_AMOUNT} 个往外送。</p>
 *
 * <p>这组断言钉的是"两套阈值口径不能互相漂移"：同一 maxStackSize 下 2 组在两边必须是同一个件数；
 * 两边的"0"含义相反（仪表 0 = 未启用，漏斗 0 被夹成 1 组），谁把夹取逻辑搬错边，这里先红。</p>
 */
class DumpGaugeFunnelLinkageTest {

    @Test
    void bothSidesStopExactlyAtTheThreshold() {
        // 仪表：存量 == 目标 → 不发包
        assertEquals(0, DumpGaugeMath.dumpAmountForUnits(2, true, 64, 128));
        assertEquals(1, DumpGaugeMath.dumpAmountForUnits(2, true, 64, 129));
        // 漏斗：总数 == 阈值 → 不动手
        assertFalse(DumpFunnelRules.shouldDump(128L, 2, 64));
        assertTrue(DumpFunnelRules.shouldDump(129L, 2, 64));
    }

    @Test
    void stackTargetsMeanTheSameOnBothSides() {
        for (int stacks = 1; stacks <= 9; stacks++) {
            assertEquals(DumpFunnelRules.thresholdItems(stacks, 64),
                    DumpGaugeMath.targetItems(stacks + 1, true, 64),
                    "滑条 raw=" + (stacks + 1) + " 的「" + stacks + " 组」必须等于漏斗的 " + stacks + " 组阈值");
        }
        // 漏斗上限 64 组、仪表上限 99 组：99 组这一档漏斗没有对应值，只能用 clampStacks 夹到 64
        assertEquals(64, DumpFunnelRules.clampStacks(DumpGaugeMath.MAX_UNITS));
    }

    @Test
    void zeroMeansOppositeThingsAndMustNotBeCopiedAcross() {
        // 仪表 raw=0 = 未启用（完全不干活），不是"目标 0 卸空"
        assertTrue(DumpGaugeMath.isInactive(DumpGaugeMath.RAW_INACTIVE));
        assertEquals(DumpGaugeMath.SIGNAL_INACTIVE, DumpGaugeMath.targetUnits(0));
        assertEquals(0, DumpGaugeMath.dumpAmount(0, true, 64, 6400));
        // 仪表"卸空"是 raw=1 / 信号 0：仍受单笔上限约束（6400 件要分多笔）
        assertEquals(0, DumpGaugeMath.targetUnits(1));
        assertEquals(64 * DumpGaugeMath.MAX_BATCH_STACKS, DumpGaugeMath.dumpAmount(1, true, 64, 6400));
        // 漏斗的 0 组会被夹成 1 组，而不是"抽干容器"
        assertEquals(DumpFunnelRules.MIN_STACKS, DumpFunnelRules.clampStacks(0));
        assertEquals(64L, DumpFunnelRules.thresholdItems(0, 64));
        assertNotEquals(DumpGaugeMath.RAW_INACTIVE, DumpFunnelRules.clampStacks(0));
    }

    @Test
    void gaugeDrainsInWholeBoxBatchesWhileTheFunnelMovesOneAtATime() {
        int maxStack = 64;
        int batch = maxStack * DumpGaugeMath.MAX_BATCH_STACKS;
        assertEquals(batch, DumpGaugeMath.dumpAmountForUnits(1, true, maxStack, batch + 100),
                "单笔最多一个潜影盒的量");
        // 漏斗每次只抽一件，所以仪表一笔订量必然被拆成很多个搬运周期；反过来只要仪表还在订，漏斗就一直有活干
        assertEquals(1, DumpFunnelRules.EXTRACT_AMOUNT);
        assertTrue(DumpGaugeMath.dumpAmountForUnits(1, true, maxStack, 2 * maxStack) > DumpFunnelRules.EXTRACT_AMOUNT);
    }

    @Test
    void repeatedRequestsConvergeToTheTargetWithoutOvershooting() {
        int maxStack = 64;
        // 单位 = 组：目标 2 组 = 128 件
        int units = 2;
        long target = DumpGaugeMath.itemsOf(units, true, maxStack);
        int storage = 5000;
        int rounds = 0;
        while (rounds < 1000) {
            int amount = DumpGaugeMath.dumpAmountForUnits(units, true, maxStack, storage);
            if (amount == 0) {
                break;
            }
            assertTrue(amount <= (long) maxStack * DumpGaugeMath.MAX_BATCH_STACKS);
            assertTrue(storage - amount >= target, "任何一轮都不许把库存压到目标以下");
            storage -= amount;
            rounds++;
        }
        assertEquals(target, (long) storage, "最终停在目标值上（" + rounds + " 轮）");
    }

    @Test
    void missingMaxStackSizeFallsBackToOneOnBothSides() {
        assertEquals(3L, DumpGaugeMath.itemsOf(3, true, 0));
        assertEquals(3L, DumpFunnelRules.thresholdItems(3, 0));
    }
}
