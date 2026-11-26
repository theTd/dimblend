package dimblend.blocks.compat.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 扳手右键状态机的自检：三条规则钉在这里——
 * <ol>
 *   <li>已连接的端点右键一律断开（在最前面，压过"不是端点/不在列车上"的提示）；</li>
 *   <li>没挂起选择时点有效端点 = 选中；再点同一个 = 取消；点别的 = 尝试建链；</li>
 *   <li>扳手离开手持即退出连接态（切出扳手自动退出）。</li>
 * </ol>
 */
class TrainTubeWrenchRulesTest {

    @Test
    void linkedEndpointAlwaysDisconnects() {
        // 已连接 + 有选择 + 点的是别的端点：仍然先断开这一端
        assertEquals(TrainTubeWrenchRules.Action.DISCONNECT,
                TrainTubeWrenchRules.decide(false, true, true, true, true, false));
        // 已连接 + 没有选择
        assertEquals(TrainTubeWrenchRules.Action.DISCONNECT,
                TrainTubeWrenchRules.decide(false, true, true, true, false, false));
        // 已连接但子层级查不到（按位置兜底断开）
        assertEquals(TrainTubeWrenchRules.Action.DISCONNECT,
                TrainTubeWrenchRules.decide(false, true, true, false, false, false));
        // 已连接但本地连接数已不是 1（异常态）：仍然允许断开，不提示"不是端点"
        assertEquals(TrainTubeWrenchRules.Action.DISCONNECT,
                TrainTubeWrenchRules.decide(false, true, false, true, false, false));
    }

    @Test
    void shiftCancelsPendingSelectionOnly() {
        assertEquals(TrainTubeWrenchRules.Action.CANCEL,
                TrainTubeWrenchRules.decide(true, false, true, true, true, false));
        assertEquals(TrainTubeWrenchRules.Action.IGNORE,
                TrainTubeWrenchRules.decide(true, false, true, true, false, false));
        // 潜行优先于断开：潜行拆管走 Create 的 onSneakWrenched，这里不能抢
        assertEquals(TrainTubeWrenchRules.Action.CANCEL,
                TrainTubeWrenchRules.decide(true, true, true, true, true, true));
    }

    @Test
    void endpointAndTrainGuardsComeBeforeSelection() {
        assertEquals(TrainTubeWrenchRules.Action.NOT_ENDPOINT,
                TrainTubeWrenchRules.decide(false, false, false, true, false, false));
        assertEquals(TrainTubeWrenchRules.Action.NOT_ENDPOINT,
                TrainTubeWrenchRules.decide(false, false, false, true, true, false));
        assertEquals(TrainTubeWrenchRules.Action.NOT_ON_TRAIN,
                TrainTubeWrenchRules.decide(false, false, true, false, false, false));
        assertEquals(TrainTubeWrenchRules.Action.NOT_ON_TRAIN,
                TrainTubeWrenchRules.decide(false, false, true, false, true, false));
    }

    @Test
    void selectionThenSameOrOtherEndpoint() {
        assertEquals(TrainTubeWrenchRules.Action.SELECT,
                TrainTubeWrenchRules.decide(false, false, true, true, false, false));
        assertEquals(TrainTubeWrenchRules.Action.CANCEL,
                TrainTubeWrenchRules.decide(false, false, true, true, true, true));
        assertEquals(TrainTubeWrenchRules.Action.LINK,
                TrainTubeWrenchRules.decide(false, false, true, true, true, false));
    }

    @Test
    void wrenchLeavesHandAbandonsSelection() {
        long timeout = 2400L;
        assertFalse(TrainTubeWrenchRules.shouldAbandon(true, 0L, timeout));
        assertFalse(TrainTubeWrenchRules.shouldAbandon(true, timeout, timeout));
        assertTrue(TrainTubeWrenchRules.shouldAbandon(false, 0L, timeout), "切走扳手当刻就该退出连接态");
        assertTrue(TrainTubeWrenchRules.shouldAbandon(false, timeout, timeout));
    }

    @Test
    void timeoutAlsoAbandonsEvenWithWrenchInHand() {
        assertTrue(TrainTubeWrenchRules.shouldAbandon(true, 2401L, 2400L));
        assertFalse(TrainTubeWrenchRules.shouldAbandon(true, 2400L, 2400L));
    }
}
