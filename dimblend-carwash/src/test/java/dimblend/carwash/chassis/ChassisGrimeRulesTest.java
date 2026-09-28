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
    void gravelOnlyForMultiplesAbove256() {
        // 256 本身不大于 256：不贴碎石；320/384/448 贴
        assertFalse(ChassisGrimeRules.hasGravel(ChassisGrimeRules.dirtLevel(256)));
        assertFalse(ChassisGrimeRules.hasGravel(ChassisGrimeRules.dirtLevel(319)));
        assertTrue(ChassisGrimeRules.hasGravel(ChassisGrimeRules.dirtLevel(320)));
        assertTrue(ChassisGrimeRules.hasGravel(ChassisGrimeRules.dirtLevel(511)));
    }

    @Test
    void soilingChanceNeedsMoreThan4MetresPerSecond() {
        assertEquals(0.0, ChassisGrimeRules.soilingChance(4.0));
        assertEquals(0.0, ChassisGrimeRules.soilingChance(Double.NaN));
        assertEquals(0.5, ChassisGrimeRules.soilingChance(6.0), 1e-9);
        assertEquals(1.0, ChassisGrimeRules.soilingChance(12.0), 1e-9);
        assertEquals(1.0, ChassisGrimeRules.soilingChance(30.0), 1e-9);
    }

    @Test
    void rainCleansFullDirtWithinTwoMinutes() {
        int seconds = (int) Math.ceil((double) ChassisGrimeRules.MAX_DIRT / ChassisGrimeRules.RAIN_WASH_PER_SECOND);
        assertTrue(seconds <= 120, "rain needs " + seconds + " s");
    }

    @Test
    void variantByteSplitsIntoDirtAndGravel() {
        ChassisGrimeVisual visual = ChassisGrimeVisual.of(400, 0xAB);
        assertEquals(6, visual.level());
        assertEquals(0x0B, visual.dirtVariant());
        assertEquals(0x0A, visual.gravelVariant());
        assertTrue(visual.hasGravel());
    }

    @Test
    void cleanBelowFirstMultiple() {
        assertTrue(ChassisGrimeVisual.of(63, 0xFF).isClean());
        assertEquals(ChassisGrimeVisual.CLEAN, ChassisGrimeVisual.of(0, 0x12));
        // 同档位同变体即相等（客户端据此决定是否重绘）
        assertEquals(ChassisGrimeVisual.of(70, 3), ChassisGrimeVisual.of(120, 3));
    }
}
