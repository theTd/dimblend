package dimblend.experience.compat.disenchantingtable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 附魔台诅咒禁令的组合口径。真实的附魔标签解析要注册表（{@code Holder#is(TagKey)}），
 * 单测只钉"四个来源任一为真即禁止"的真值表，以及它确实是 OR（不是 AND）。
 */
class DisenchantingCurseRulesTest {

    @Test
    void anySingleSourceBlocks() {
        assertTrue(DisenchantingCurseRules.shouldBlock(true, false, false, false), "装备上的诅咒");
        assertTrue(DisenchantingCurseRules.shouldBlock(false, true, false, false), "附魔书库存里的诅咒");
        assertTrue(DisenchantingCurseRules.shouldBlock(false, false, true, false), "绑定诅咒效果组件兜底");
        assertTrue(DisenchantingCurseRules.shouldBlock(false, false, false, true), "消失诅咒效果组件兜底");
    }

    @Test
    void cleanItemIsAllowed() {
        assertFalse(DisenchantingCurseRules.shouldBlock(false, false, false, false));
    }

    @Test
    void allSourcesTogetherStillJustBlocks() {
        assertTrue(DisenchantingCurseRules.shouldBlock(true, true, true, true));
    }
}
