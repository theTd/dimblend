package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 右键短按的分支判定。
 *
 * <p>断言的对象是 Create 6.0.10 的真实调用链（{@code ValueSettingsInputHandler} →
 * {@code ValueSettingsClient#tick} → {@code ValueSettingsPacket#applySettings} →
 * {@code AbstractPanelBehaviour#onShortInteract}），所以每个用例都成对检查「客户端 / 服务端」
 * 两侧，避免出现「一侧开了界面、另一侧同时改了状态」这种原缺陷。</p>
 */
class DumpGaugeInteractionTest {

    private static DumpGaugeInteraction.Action route(boolean client, boolean filterEmpty, boolean emptyHand,
            boolean frequencyItem, boolean filterCandidate) {
        return DumpGaugeInteraction.route(
                new DumpGaugeInteraction.Click(client, filterEmpty, emptyHand, frequencyItem, filterCandidate));
    }

    @Test
    void holdingAPlainItemNeverOpensTheScreen() {
        // 原缺陷：客户端拿着物品也走 displayScreen，设置页把「设过滤」抢掉
        DumpGaugeInteraction.Action client = route(true, true, false, false, true);
        assertEquals(DumpGaugeInteraction.Action.CONSUME_ONLY, client);
        assertEquals(DumpGaugeInteraction.Action.APPLY_FILTER, route(false, true, false, false, true));
    }

    @Test
    void settingTheFilterAlsoWorksWhenOneIsAlreadySet() {
        // 过滤槽非空时，右键会先进 value-settings 的短按路径，但双端仍会落到 5 参钩子
        assertEquals(DumpGaugeInteraction.Action.APPLY_FILTER, route(false, false, false, false, true));
        assertEquals(DumpGaugeInteraction.Action.CONSUME_ONLY, route(true, false, false, false, true));
    }

    @Test
    void emptyHandWithEmptyFilterOpensCreateSetItemMenu() {
        // 与 Create 的 FactoryPanelBehaviour#onShortInteract 同口径：空过滤槽 → 选物品界面
        assertEquals(DumpGaugeInteraction.Action.OPEN_SET_ITEM_MENU, route(false, true, true, false, false));
        assertEquals(DumpGaugeInteraction.Action.CONSUME_ONLY, route(true, true, true, false, false));
    }

    @Test
    void emptyHandWithAnItemSetOpensTheDumpScreenOnTheClientOnly() {
        assertEquals(DumpGaugeInteraction.Action.OPEN_SCREEN, route(true, false, true, false, false));
        // 服务端绝不能自己去 displayScreen（那边只会空转）
        assertEquals(DumpGaugeInteraction.Action.IGNORE, route(false, false, true, false, false));
    }

    @Test
    void frequencyItemsBindTheNetworkOnTheServer() {
        assertEquals(DumpGaugeInteraction.Action.ASSIGN_FREQUENCY, route(false, true, false, true, false));
        assertEquals(DumpGaugeInteraction.Action.ASSIGN_FREQUENCY, route(false, false, false, true, false));
        assertEquals(DumpGaugeInteraction.Action.CONSUME_ONLY, route(true, false, false, true, false));
    }

    @Test
    void toolsNeverPopTheScreenOrBecomeAFilter() {
        // 扳手/机械臂：两侧都不动（扳手留给连线走向循环，机械臂是要放下的方块）
        assertEquals(DumpGaugeInteraction.Action.IGNORE, route(true, false, false, false, false));
        assertEquals(DumpGaugeInteraction.Action.IGNORE, route(false, false, false, false, false));
    }

    @Test
    void onlyTheServerEverWritesWorldState() {
        // 把矩阵里所有会改状态的动作用例抄一遍，逐个确认「客户端侧」不会拿到服务端动作
        for (boolean filterEmpty : new boolean[] {true, false}) {
            for (boolean emptyHand : new boolean[] {true, false}) {
                for (boolean frequencyItem : new boolean[] {true, false}) {
                    for (boolean candidate : new boolean[] {true, false}) {
                        DumpGaugeInteraction.Action server = route(false, filterEmpty, emptyHand, frequencyItem,
                                candidate);
                        DumpGaugeInteraction.Action client = route(true, filterEmpty, emptyHand, frequencyItem,
                                candidate);
                        assertNotNull(server);
                        assertNotNull(client);
                        assertFalse(client == DumpGaugeInteraction.Action.APPLY_FILTER,
                                "客户端不能写过滤槽");
                        assertFalse(client == DumpGaugeInteraction.Action.ASSIGN_FREQUENCY,
                                "客户端不能绑频率");
                        assertFalse(client == DumpGaugeInteraction.Action.OPEN_SET_ITEM_MENU,
                                "打开选物品界面是服务端 openMenu");
                    }
                }
            }
        }
    }

    @Test
    void everyRouteIsOneOfTheSixActions() {
        for (boolean client : new boolean[] {true, false}) {
            for (boolean filterEmpty : new boolean[] {true, false}) {
                for (boolean emptyHand : new boolean[] {true, false}) {
                    for (boolean frequencyItem : new boolean[] {true, false}) {
                        for (boolean candidate : new boolean[] {true, false}) {
                            assertNotNull(route(client, filterEmpty, emptyHand, frequencyItem, candidate));
                        }
                    }
                }
            }
        }
        assertEquals(6, DumpGaugeInteraction.Action.values().length);
    }

    @Test
    void wrenchAndMechanicalArmAreNotFilters() {
        assertTrue(DumpGaugeInteraction.isFilterCandidate(false, false, false), "普通物品可以设过滤");
        assertFalse(DumpGaugeInteraction.isFilterCandidate(true, false, false), "空手不是过滤物品");
        assertFalse(DumpGaugeInteraction.isFilterCandidate(false, true, false), "扳手要留给连线循环");
        assertFalse(DumpGaugeInteraction.isFilterCandidate(false, false, true), "机械臂是要放下的方块");
    }
}
