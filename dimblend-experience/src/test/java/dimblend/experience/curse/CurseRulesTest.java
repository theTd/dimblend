package dimblend.experience.curse;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.entity.EquipmentSlot;
import org.junit.jupiter.api.Test;

/**
 * 两条诅咒的纯逻辑：只接收调用方已经取好的事实（槽位、空栈、是否带诅咒、掷骰值、按键码），
 * 不接触实体与世界，单测直接钉边界。
 *
 * <p>诅咒判据本身走原版 {@code EnchantmentHelper.has(stack, EnchantmentEffectComponents.*)}，
 * 这里不做重复解释——1.21.1 的 PREVENT_ARMOR_CHANGE / PREVENT_EQUIPMENT_DROP 是
 * 附魔效果组件，不是物品栈上的独立数据组件。</p>
 */
class CurseRulesTest {

    // ---- 绑定诅咒：脱下触发 ----

    @Test
    void bindingCurseAppliesWhenTheCursedItemIsTheLastOneLeaving() {
        assertTrue(CurseRules.shouldApplyBindingCurseOnRemoval(false, true, true, false));
    }

    @Test
    void bindingCurseDoesNotApplyWhileAnotherCursedItemStaysWorn() {
        assertFalse(CurseRules.shouldApplyBindingCurseOnRemoval(false, true, true, true));
    }

    @Test
    void bindingCurseOnlyFiresWhenTheSlotActuallyBecomesEmpty() {
        // 脱之前槽本来就是空的：不触发。
        assertFalse(CurseRules.shouldApplyBindingCurseOnRemoval(true, true, true, false));
        // 换装（槽仍然有东西）：不算脱下。
        assertFalse(CurseRules.shouldApplyBindingCurseOnRemoval(false, false, true, false));
        // 离开的那件不带绑定诅咒：不触发。
        assertFalse(CurseRules.shouldApplyBindingCurseOnRemoval(false, true, false, false));
        assertTrue(CurseRules.shouldApplyBindingCurseOnRemoval(false, true, true, false));
    }

    // ---- 绑定诅咒：重新穿上解除 ----

    @Test
    void equippingABindingCurseItemClearsTheCurse() {
        assertTrue(CurseRules.shouldClearBindingCurseOnEquip(false, true));
    }

    @Test
    void equippingAnythingElseDoesNotClearTheCurse() {
        // 槽被清空（脱下）。
        assertFalse(CurseRules.shouldClearBindingCurseOnEquip(true, false));
        // 换上一件普通装备。
        assertFalse(CurseRules.shouldClearBindingCurseOnEquip(false, false));
        // 槽空却"带诅咒"是不可能组合，不能据此解除。
        assertFalse(CurseRules.shouldClearBindingCurseOnEquip(true, true));
    }

    // ---- 消失诅咒：掷骰范围 ----

    @Test
    void vanishingCurseScopeIsFourArmorSlotsAndBothHands() {
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            assertTrue(CurseRules.isCursedEquipmentSlot(slot), slot.getName());
        }
        assertFalse(CurseRules.isCursedEquipmentSlot(EquipmentSlot.BODY));
    }

    @Test
    void vanishChanceIsFiveInTenThousandPerClick() {
        assertEquals(0.0005D, CurseRules.VANISH_CHANCE_PER_CLICK);
    }

    @Test
    void vanishRollIsStrictlyBelowTheThreshold() {
        assertTrue(CurseRules.shouldVanishOnClick(false, true, 0.0D));
        assertTrue(CurseRules.shouldVanishOnClick(false, true, 0.000499D));
        assertFalse(CurseRules.shouldVanishOnClick(false, true, 0.0005D));
        assertFalse(CurseRules.shouldVanishOnClick(false, true, 0.999D));
    }

    @Test
    void vanishNeedsNonCreativeAndTheCurse() {
        // 创造模式不掷骰。
        assertFalse(CurseRules.shouldVanishOnClick(true, true, 0.0D));
        // 该件没有消失诅咒。
        assertFalse(CurseRules.shouldVanishOnClick(false, false, 0.0D));
        assertTrue(CurseRules.shouldVanishOnClick(false, true, 0.0D));
    }

    // ---- 消失诅咒：点击范围 ----

    @Test
    void everyLeftOrRightPressCounts() {
        // 左键 0 / 右键 1，action 1 = GLFW_PRESS；GUI 点击与空挥同一路径，这里不做界面判断。
        assertTrue(CurseRules.isTrackedMouseClick(0, 1));
        assertTrue(CurseRules.isTrackedMouseClick(1, 1));
    }

    @Test
    void releasesMiddleAndOtherButtonsDoNotCount() {
        assertFalse(CurseRules.isTrackedMouseClick(0, 0));
        assertFalse(CurseRules.isTrackedMouseClick(1, 0));
        assertFalse(CurseRules.isTrackedMouseClick(2, 1));
        assertFalse(CurseRules.isTrackedMouseClick(3, 1));
    }
}
