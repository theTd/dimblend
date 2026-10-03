package dimblend.carwash.chassis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChassisGrimeRulesTest {

    @Test
    void dirtIsClampedTo0Through511() {
        assertEquals(0, ChassisGrimeRules.clampDirt(-32));
        assertEquals(511, ChassisGrimeRules.clampDirt(600));
        assertEquals(40, ChassisGrimeRules.clampDirt(40));
    }

    @Test
    void textureLevelStepsEvery64() {
        assertEquals(0, ChassisGrimeRules.dirtLevel(63));
        assertEquals(1, ChassisGrimeRules.dirtLevel(64));
        assertEquals(4, ChassisGrimeRules.dirtLevel(256));
        assertEquals(7, ChassisGrimeRules.dirtLevel(448));
        assertEquals(7, ChassisGrimeRules.dirtLevel(511));
        assertEquals(7, ChassisGrimeRules.MAX_LEVEL);
    }

    @Test
    void gravelOnlyWhenDirtAbove256() {
        // 256 本身不大于 256：不贴碎石；257 起贴
        assertFalse(ChassisGrimeRules.hasGravel(256));
        assertTrue(ChassisGrimeRules.hasGravel(257));
        assertTrue(ChassisGrimeRules.hasGravel(511));
    }

    @Test
    void soilingChanceNeedsMoreThan4MetresPerSecond() {
        assertEquals(0.0, ChassisGrimeRules.soilingChance(4.0));
        assertEquals(0.0, ChassisGrimeRules.soilingChance(Double.NaN));
        // 概率 = (速度/12) × 0.5，24 m/s 封顶 100%
        assertEquals(0.25, ChassisGrimeRules.soilingChance(6.0), 1e-9);
        assertEquals(0.5, ChassisGrimeRules.soilingChance(12.0), 1e-9);
        assertEquals(1.0, ChassisGrimeRules.soilingChance(24.0), 1e-9);
        assertEquals(1.0, ChassisGrimeRules.soilingChance(30.0), 1e-9);
    }

    @Test
    void rainWashesDownToFloor64() {
        // 每秒 50% 概率 −1，降到 64 停止；期望耗时 (511−64)/0.5 = 894 秒
        assertEquals(1, ChassisGrimeRules.RAIN_WASH_AMOUNT);
        assertEquals(0.5, ChassisGrimeRules.RAIN_WASH_CHANCE, 1e-9);
        assertEquals(64, ChassisGrimeRules.RAIN_WASH_FLOOR);
        assertTrue(ChassisGrimeRules.RAIN_WASH_FLOOR < ChassisGrimeRules.MAX_DIRT);
    }

    @Test
    void configurableMultiplierChangesChanceAndZeroDisablesSoiling() {
        assertEquals(0.0D, ChassisGrimeRules.soilingChance(100.0D, 0.0D));
        assertEquals(0.0D, ChassisGrimeRules.soilingChance(4.0D, 1.0D));
        assertEquals(0.01D, ChassisGrimeRules.soilingChance(12.0D, 0.01D), 1.0E-9D);
        assertEquals(0.5D, ChassisGrimeRules.soilingChance(12.0D, 0.5D), 1.0E-9D);
        assertEquals(1.0D, ChassisGrimeRules.soilingChance(12.0D, 1.0D));
        assertEquals(1.0D, ChassisGrimeRules.soilingChance(30.0D, 1.0D));
    }

    @Test
    void variantByteSplitsIntoDirtAndGravel() {
        ChassisGrimeVisual visual = ChassisGrimeVisual.of(400, 0xAB);
        assertEquals(6, visual.level());
        assertEquals(0x0B, visual.dirtVariant());
        assertEquals(0x0A, visual.gravelVariant());
        assertTrue(visual.hasGravel());
        // 碎石边界：脏值 256 不贴，257 起贴（同在第 4 档内翻转）
        assertFalse(ChassisGrimeVisual.of(256, 0xAB).hasGravel());
        assertTrue(ChassisGrimeVisual.of(257, 0xAB).hasGravel());
    }

    @Test
    void cleanBelowFirstMultiple() {
        assertTrue(ChassisGrimeVisual.of(63, 0xFF).isClean());
        assertEquals(ChassisGrimeVisual.CLEAN, ChassisGrimeVisual.of(0, 0x12));
        // 同档位同变体即相等（客户端据此决定是否重绘）
        assertEquals(ChassisGrimeVisual.of(70, 3), ChassisGrimeVisual.of(120, 3));
    }
}
