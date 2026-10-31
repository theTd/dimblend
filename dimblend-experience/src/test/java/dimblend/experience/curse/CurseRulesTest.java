package dimblend.experience.curse;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.entity.EquipmentSlot;
import org.junit.jupiter.api.Test;

class CurseRulesTest {

    @Test
    void bindingCurseRemovalOnlyFiresForHumanoidArmor() {
        for (EquipmentSlot armor : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            assertTrue(CurseRules.isBindingCurseArmorRemoval(armor, false, true, true), armor.getName());
        }
        for (EquipmentSlot other : new EquipmentSlot[] {
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.BODY}) {
            assertFalse(CurseRules.isBindingCurseArmorRemoval(other, false, true, true), other.getName());
        }
    }

    @Test
    void bindingCurseRemovalNeedsTheCursedItemToActuallyLeave() {
        // 脱下的那件不带绑定诅咒：不触发。
        assertFalse(CurseRules.isBindingCurseArmorRemoval(EquipmentSlot.HEAD, false, true, false));
        // 脱之前槽本来就是空的：不触发。
        assertFalse(CurseRules.isBindingCurseArmorRemoval(EquipmentSlot.HEAD, true, true, true));
        // 换装（槽仍然有东西）：不算脱下。
        assertFalse(CurseRules.isBindingCurseArmorRemoval(EquipmentSlot.HEAD, false, false, true));
        assertTrue(CurseRules.isBindingCurseArmorRemoval(EquipmentSlot.HEAD, false, true, true));
    }

    @Test
    void cursedEquipmentScopeIsFourArmorSlotsAndBothHands() {
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            assertTrue(CurseRules.isCursedEquipmentSlot(slot), slot.getName());
        }
        assertFalse(CurseRules.isCursedEquipmentSlot(EquipmentSlot.BODY));
    }

    @Test
    void vanishChanceIsPointTwoPercentPerHit() {
        assertEquals(0.002D, CurseRules.VANISH_CHANCE_PER_HIT);
    }

    @Test
    void vanishRollIsStrictlyBelowPointTwoPercent() {
        assertTrue(CurseRules.shouldVanishOnHit(true, false, true, 0.0D));
        assertTrue(CurseRules.shouldVanishOnHit(true, false, true, 0.001999D));
        assertFalse(CurseRules.shouldVanishOnHit(true, false, true, 0.002D));
        assertFalse(CurseRules.shouldVanishOnHit(true, false, true, 0.999D));
    }

    @Test
    void vanishNeedsRealDamageNonCreativeAndTheCurse() {
        assertFalse(CurseRules.shouldVanishOnHit(false, false, true, 0.0D));
        assertFalse(CurseRules.shouldVanishOnHit(true, true, true, 0.0D));
        assertFalse(CurseRules.shouldVanishOnHit(true, false, false, 0.0D));
        assertTrue(CurseRules.shouldVanishOnHit(true, false, true, 0.0D));
    }

    /** 与 1.21.1 PoisonMobEffect#shouldApplyEffectTickThisTick 同口径：25 >> amplifier。 */
    @Test
    void cursedPoisonTicksExactlyLikePoison() {
        assertTrue(CurseRules.cursedPoisonTicksOn(25, 0));
        assertFalse(CurseRules.cursedPoisonTicksOn(24, 0));
        assertTrue(CurseRules.cursedPoisonTicksOn(0, 0));
        assertTrue(CurseRules.cursedPoisonTicksOn(12, 1));
        assertFalse(CurseRules.cursedPoisonTicksOn(11, 1));
        assertTrue(CurseRules.cursedPoisonTicksOn(6, 2));
        assertFalse(CurseRules.cursedPoisonTicksOn(5, 2));
        // 25>>5 == 0 → 每 tick 都触发。
        assertTrue(CurseRules.cursedPoisonTicksOn(1, 5));
        assertTrue(CurseRules.cursedPoisonTicksOn(23, 5));
    }

    /** 与 1.21.1 PoisonMobEffect#applyEffectTick 同口径：血量 > 1 才扣伤害。 */
    @Test
    void cursedPoisonOnlyDamagesAboveOneHealth() {
        assertFalse(CurseRules.cursedPoisonDamages(0.5F));
        assertFalse(CurseRules.cursedPoisonDamages(1.0F));
        assertTrue(CurseRules.cursedPoisonDamages(1.001F));
        assertTrue(CurseRules.cursedPoisonDamages(20.0F));
    }
}
