package dimblend.experience.compat.coldsweat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** LOCKED DECISION #15：容量 256 / 强度 ≥0.80 / 三段火焰 171-85 / 燃料条 30px。 */
class SoulspringLampRulesTest {

    @Test
    void capacityIsFourTimesVanilla() {
        assertEquals(256.0D, SoulspringLampRules.CAPACITY);
        assertEquals(4.0D, SoulspringLampRules.CAPACITY / SoulspringLampRules.LEGACY_CAPACITY);
    }

    @Test
    void fuelIsClampedIntoStorageRange() {
        assertEquals(0.0D, SoulspringLampRules.clampFuel(-1.0D));
        assertEquals(0.0D, SoulspringLampRules.clampFuel(Double.NaN));
        assertEquals(256.0D, SoulspringLampRules.clampFuel(300.0D));
        assertEquals(123.5D, SoulspringLampRules.clampFuel(123.5D));
    }

    @Test
    void strengthFloorRaisesVanillaDefaultAndKeepsUserTuning() {
        assertEquals(0.80D, SoulspringLampRules.effectiveStrength(SoulspringLampRules.VANILLA_STRENGTH));
        assertEquals(0.80D, SoulspringLampRules.effectiveStrength(0.1D));
        assertEquals(0.95D, SoulspringLampRules.effectiveStrength(0.95D));
    }

    @Test
    void tooltipWidthUsesTheNewDivisorAndStaysWithin30Pixels() {
        assertEquals(0, SoulspringLampRules.tooltipWidth(0.0D));
        assertEquals(8, SoulspringLampRules.tooltipWidth(64.0D));   // 64*30/256 = 7.5 → round 8
        assertEquals(30, SoulspringLampRules.tooltipWidth(256.0D));
        assertEquals(30, SoulspringLampRules.tooltipWidth(9999.0D));
    }

    @Test
    void tooltipWidthMatchesOldGeometryAtTheOldCapacity() {
        // 旧容量 64 时原版画 64/2.1333 = 30px（满条）；新公式在 64 时只画四分之一。
        assertTrue(SoulspringLampRules.tooltipWidth(256.0D) == 30);
        assertTrue(SoulspringLampRules.tooltipWidth(128.0D) == 15);
    }

    @Test
    void divisorIs256Over30() {
        assertEquals(256.0D / 30.0D, SoulspringLampRules.TOOLTIP_DIVISOR);
    }

    @Test
    void flameTiersUse171And85() {
        assertEquals(1, SoulspringLampRules.flameTier(0.5D));
        assertEquals(1, SoulspringLampRules.flameTier(85.0D));
        assertEquals(2, SoulspringLampRules.flameTier(85.5D));
        assertEquals(2, SoulspringLampRules.flameTier(171.0D));
        assertEquals(3, SoulspringLampRules.flameTier(171.5D));
        assertEquals(3, SoulspringLampRules.flameTier(256.0D));
    }

    @Test
    void modelStateIsZeroWhenUnlit() {
        assertEquals(0.0F, SoulspringLampRules.modelState(false, 256.0D));
        assertEquals(3.0F, SoulspringLampRules.modelState(true, 256.0D));
    }
}
