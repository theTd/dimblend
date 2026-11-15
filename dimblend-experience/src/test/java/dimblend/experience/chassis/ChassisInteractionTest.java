package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChassisInteractionTest {
    @Test
    void onlyMainHandEmptyOrWrenchOpensWhenApplicable() {
        assertEquals(ChassisInteraction.Action.OPEN, ChassisInteraction.decide(true, false, true, true, true));
        assertEquals(ChassisInteraction.Action.PASS, ChassisInteraction.decide(true, true, true, true, true));
        assertEquals(ChassisInteraction.Action.PASS, ChassisInteraction.decide(true, false, false, true, true));
        assertEquals(ChassisInteraction.Action.PASS, ChassisInteraction.decide(true, false, true, false, true));
    }

    @Test
    void offHandNeverOpens() {
        for (boolean sneaking : new boolean[]{false, true}) {
            for (boolean menuTrigger : new boolean[]{false, true}) {
                for (boolean applicable : new boolean[]{false, true}) {
                    for (boolean creative : new boolean[]{false, true}) {
                        assertEquals(ChassisInteraction.Action.PASS,
                                ChassisInteraction.decide(false, sneaking, menuTrigger, applicable, creative));
                    }
                }
            }
        }
    }

    /**
     * 2026-11-15：驾驶台默认只给创造模式（{@link ChassisInteraction#SURVIVAL_STANDARD_CHASSIS_ENABLED}
     * 为 false）。生存玩家必须走原版路径，不能出现“取消点击但没开菜单”的吞点击。
     */
    @Test
    void survivalOnlyOpensTheMenuWhenTheFallbackIsEnabled() {
        assertFalse(ChassisInteraction.SURVIVAL_STANDARD_CHASSIS_ENABLED,
                "默认口径：驾驶台是创造模式调试专用");
        assertEquals(ChassisInteraction.SURVIVAL_STANDARD_CHASSIS_ENABLED,
                ChassisInteraction.decide(true, false, true, true, false) == ChassisInteraction.Action.OPEN);
        assertEquals(ChassisInteraction.Action.OPEN,
                ChassisInteraction.decide(true, false, true, true, true));
    }

    @Test
    void everyOtherCombinationPasses() {
        for (boolean mainHand : new boolean[]{false, true}) {
            for (boolean sneaking : new boolean[]{false, true}) {
                for (boolean menuTrigger : new boolean[]{false, true}) {
                    for (boolean applicable : new boolean[]{false, true}) {
                        for (boolean creative : new boolean[]{false, true}) {
                            boolean open = mainHand && !sneaking && menuTrigger && applicable
                                    && (creative || ChassisInteraction.SURVIVAL_STANDARD_CHASSIS_ENABLED);
                            assertEquals(open ? ChassisInteraction.Action.OPEN : ChassisInteraction.Action.PASS,
                                    ChassisInteraction.decide(mainHand, sneaking, menuTrigger, applicable, creative));
                        }
                    }
                }
            }
        }
    }

    /**
     * 空手与 Create 扳手是仅有的两个触发器。
     *
     * <p>这条钉死“其他物品必须放行”的入口：若 {@code menuTrigger} 改成非空即真，或把某种
     * 工具误当扳手，下面的 {@code false} 断言会红，而不是等到实机发现普通右键被吞。
     */
    @Test
    void onlyEmptyHandAndCreateWrenchAreTriggers() {
        assertTrue(ChassisInteraction.menuTrigger(true, false), "an empty main hand opens the menu");
        assertTrue(ChassisInteraction.menuTrigger(false, true), "Create's wrench opens the menu");
        assertTrue(ChassisInteraction.menuTrigger(true, true), "both inputs may hold at once");
        assertFalse(ChassisInteraction.menuTrigger(false, false), "any other item must pass through");
    }

    /**
     * 触发器与潜行/物品三者的组合：非触发器在任何臂位、任何潜行状态、任何适用条件下都不开菜单。
     *
     * <p>这是“其他物品必须走正常放置/使用”的可执行版本——服务端不取消事件，客户端也就不预测
     * 成功，两端都不会吞点击。
     */
    @Test
    void nonTriggerItemsAlwaysPassThrough() {
        for (boolean mainHand : new boolean[]{false, true}) {
            for (boolean sneaking : new boolean[]{false, true}) {
                for (boolean applicable : new boolean[]{false, true}) {
                    for (boolean creative : new boolean[]{false, true}) {
                        assertEquals(ChassisInteraction.Action.PASS,
                                ChassisInteraction.decide(mainHand, sneaking,
                                        ChassisInteraction.menuTrigger(false, false), applicable, creative));
                    }
                }
            }
        }
    }
}
