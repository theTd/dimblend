package dimblend.experience.compat.simulated;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 速度传感器 km/h / mph 换算口径（2026-11-26 slice-1 #1A）。
 *
 * <p>口径对齐 {@code VelocitySensorDisplaySource#provideLine}：数值一位小数、带单位、
 * 新单位保留 {@code getAdjustedVelocity()} 的正负方向。</p>
 */
class VelocityUnitConversionTest {

    @Test
    void kilometresPerHourIsThreePointSixTimesMetresPerSecond() {
        assertEquals(36.0D, VelocityUnitConversion.kilometresPerHour(10.0D));
        assertEquals(-43.2D, VelocityUnitConversion.kilometresPerHour(-12.0D));
        assertEquals(0.0D, VelocityUnitConversion.kilometresPerHour(0.0D));
    }

    @Test
    void milesPerHourUsesExactFeetPerMetreRatio() {
        // 3600 / 1609.344，不是 3.6/1.6 那种近似
        assertEquals(2.2369362920544025D, VelocityUnitConversion.MPH_PER_MPS, 1.0E-15D);
        assertEquals(22.369362920544025D, VelocityUnitConversion.milesPerHour(10.0D), 1.0E-12D);
    }

    @Test
    void oneDecimalWithUnitAndSignKept() {
        assertEquals("12.3 km/h", VelocityUnitConversion.kilometresPerHourText(3.42D));
        assertEquals("-12.3 km/h", VelocityUnitConversion.kilometresPerHourText(-3.42D));
        assertEquals("7.6 mph", VelocityUnitConversion.milesPerHourText(3.4D));
        assertEquals("-7.6 mph", VelocityUnitConversion.milesPerHourText(-3.4D));
        assertEquals("0.0 km/h", VelocityUnitConversion.kilometresPerHourText(0.0D));
    }

    @Test
    void positiveValuesCarryNoPlusSign() {
        assertEquals("36.0 km/h", VelocityUnitConversion.kilometresPerHourText(10.0D));
    }

    @Test
    void negativeZeroIsNormalisedAway() {
        // -0.01 m/s → -0.036 km/h，按 %.1f 会写成 "-0.0"，必须归一成 "0.0"
        assertEquals("0.0 km/h", VelocityUnitConversion.kilometresPerHourText(-0.01D));
        assertEquals("0.0 mph", VelocityUnitConversion.milesPerHourText(-0.001D));
    }

    @Test
    void roundsToTheNearestTenth() {
        assertEquals("0.1 km/h", VelocityUnitConversion.kilometresPerHourText(0.03D));
        assertEquals("-0.1 km/h", VelocityUnitConversion.kilometresPerHourText(-0.03D));
        assertEquals("123.5 km/h", VelocityUnitConversion.kilometresPerHourText(34.305D));
    }

    @Test
    void nonFiniteValuesFallBackToZero() {
        assertEquals("0.0 km/h", VelocityUnitConversion.kilometresPerHourText(Double.NaN));
        assertEquals("0.0 mph", VelocityUnitConversion.milesPerHourText(Double.POSITIVE_INFINITY));
        assertEquals("0.0 km/h", VelocityUnitConversion.kilometresPerHourText(Double.NEGATIVE_INFINITY));
    }

    @Test
    void unitSuffixAlwaysHasExactlyOneSpace() {
        assertEquals("36.0 km/h", VelocityUnitConversion.format(10.0D,
                VelocityUnitConversion.KMH_PER_MPS, VelocityUnitConversion.KMH_UNIT));
        assertEquals("-22.4 mph", VelocityUnitConversion.format(-10.0D,
                VelocityUnitConversion.MPH_PER_MPS, VelocityUnitConversion.MPH_UNIT));
    }
}
