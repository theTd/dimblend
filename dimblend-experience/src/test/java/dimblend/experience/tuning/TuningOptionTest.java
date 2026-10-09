package dimblend.experience.tuning;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TuningOptionTest {
    @Test
    void intervalAllowsDisabledValueAndTenthsOnly() {
        var option = TuningOption.SHAKE_INTERVAL;
        for (double valid : new double[] {0.0D, 0.1D, 2.0D, 59.9D, 60.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {-0.1D, 0.01D, 1.0E-10D, 2.05D, 60.1D}) {
            assertFalse(option.isValid(invalid));
        }
    }

    @Test
    void forceRejectsFractionsAndOutOfRangeValues() {
        var option = TuningOption.SHAKE_FORCE;
        assertTrue(option.isValid(0.0D));
        assertTrue(option.isValid(10000.0D));
        assertFalse(option.isValid(1200.1D));
        assertFalse(option.isValid(10001.0D));
    }

    @Test
    void multiplierAcceptsEveryHundredthWithoutFloatingPointArtifacts() {
        var option = TuningOption.SOILING_MULTIPLIER;
        for (int i = 0; i <= 100; i++) {
            assertTrue(option.isValid(i / 100.0D));
        }
        assertFalse(option.isValid(0.505D));
        assertFalse(option.isValid(1.01D));
    }

    @Test
    void expClearRatioAcceptsEveryHundredthAndCapsAtOne() {
        var option = TuningOption.EXP_CLEAR_RATIO;
        for (int i = 0; i <= 100; i++) {
            assertTrue(option.isValid(i / 100.0D));
        }
        assertFalse(option.isValid(0.505D));
        assertFalse(option.isValid(1.01D));
    }

    @Test
    void radioIntensityAcceptsHundredthsUpToDouble() {
        var option = TuningOption.RADIO_ACOUSTIC_INTENSITY;
        for (int i = 0; i <= 200; i++) {
            assertTrue(option.isValid(i / 100.0D));
        }
        assertFalse(option.isValid(2.01D));
        assertFalse(option.isValid(1.005D));
        assertEquals("1.00", option.format(option.defaultValue()));
        assertTrue(TuningOption.RADIO_STATIC.isCheckbox());
        assertEquals(1.0D, TuningOption.RADIO_STATIC.defaultValue());
    }

    @Test
    void booleanPayloadsCannotSmuggleArbitraryNumericValues() {
        for (var option : TuningOption.values()) {
            assertFalse(option.isValid(Double.NaN));
            assertFalse(option.isValid(Double.POSITIVE_INFINITY));
            assertFalse(option.isValid(Double.MIN_VALUE));
            assertTrue(option.isValid(option.defaultValue()));
            if (option.isCheckbox()) {
                assertTrue(option.isValid(0.0D));
                assertTrue(option.isValid(1.0D));
                assertFalse(option.isValid(0.5D));
            }
        }
        assertNull(TuningOption.byId("unknown"));
    }

    @Test
    void formattingKeepsDeclaredPrecisionAndUsesDecimalPoint() {
        assertEquals("2.0", TuningOption.SHAKE_INTERVAL.format(2.0D));
        assertEquals("1200", TuningOption.SHAKE_FORCE.format(1200.0D));
        assertEquals("0.50", TuningOption.SOILING_MULTIPLIER.format(0.5D));
        assertEquals("1.00", TuningOption.EXP_CLEAR_RATIO.format(1.0D));
    }
}
