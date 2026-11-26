package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 右键短按的分支判定（「手持物品设过滤 / 空手开页面 / 扳手机械臂不打扰」）。 */
class DumpGaugeInteractionTest {

    private static DumpGaugeInteraction.Action route(boolean clientSide, boolean emptyHand, boolean filterCandidate) {
        return DumpGaugeInteraction.route(clientSide, emptyHand, filterCandidate);
    }

    @Test
    void holdingAnItemNeverOpensTheScreenOnTheClient() {
        // 这正是原缺陷：客户端拿着物品时也走 displayScreen，于是设置页抢掉设过滤的操作
        DumpGaugeInteraction.Action client = route(true, false, true);
        assertNotEquals(DumpGaugeInteraction.Action.OPEN_SCREEN, client);
        assertSame(DumpGaugeInteraction.Action.CONSUME_ONLY, client);
    }

    @Test
    void serverIsTheOnlySideThatWritesTheFilter() {
        assertSame(DumpGaugeInteraction.Action.APPLY_FILTER, route(false, false, true));
        // 客户端的同一次交互不能去写世界状态
        assertNotEquals(DumpGaugeInteraction.Action.APPLY_FILTER, route(true, false, true));
    }

    @Test
    void emptyHandOpensTheScreenOnlyOnTheClient() {
        assertSame(DumpGaugeInteraction.Action.OPEN_SCREEN, route(true, true, false));
        assertSame(DumpGaugeInteraction.Action.IGNORE, route(false, true, false));
    }

    @Test
    void toolsNeverPopTheScreenOrBecomeAFilter() {
        // 扳手/机械臂：客户端也不能开页面（扳手留给循环连线，机械臂是要放下的方块）
        assertSame(DumpGaugeInteraction.Action.IGNORE, route(true, false, false));
        assertSame(DumpGaugeInteraction.Action.IGNORE, route(false, false, false));
    }

    @Test
    void wrenchAndMechanicalArmAreNotFilters() {
        assertTrue(DumpGaugeInteraction.isFilterCandidate(false, false, false), "普通物品可以设过滤");
        assertFalse(DumpGaugeInteraction.isFilterCandidate(true, false, false), "空手不是过滤物品");
        assertFalse(DumpGaugeInteraction.isFilterCandidate(false, true, false), "扳手要留给连线循环");
        assertFalse(DumpGaugeInteraction.isFilterCandidate(false, false, true), "机械臂是要放下的方块");
    }

    @Test
    void everyCombinationIsCovered() {
        // 2（客户端/服务端）× 2（空手/不空手）× 2（是/不是过滤物品）八格都不能是 null
        for (boolean client : new boolean[]{true, false}) {
            for (boolean empty : new boolean[]{true, false}) {
                for (boolean candidate : new boolean[]{true, false}) {
                    assertNotNull(route(client, empty, candidate),
                            "client=" + client + " empty=" + empty + " candidate=" + candidate);
                }
            }
        }
        assertEquals(4, DumpGaugeInteraction.Action.values().length);
    }
}
