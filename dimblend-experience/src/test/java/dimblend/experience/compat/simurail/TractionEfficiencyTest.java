package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TractionEfficiencyTest {
    @Test
    void usesMetresPerSecondAndSubtractsOneAfterDividingRpm() {
        assertEquals(100.0, TractionEfficiency.percentage(2, 12).orElseThrow(), 1e-10);
        assertEquals(50.0, TractionEfficiency.percentage(31.5, 256).orElseThrow(), 1e-10);
        assertEquals("33.33%", TractionEfficiency.format(1, 16));
    }

    @Test
    void bothInputDirectionsUseTheirAbsoluteValues() {
        for (double speed : new double[] {-1.5, 1.5}) {
            for (double rpm : new double[] {-16, 16}) {
                assertEquals("50.00%", TractionEfficiency.format(speed, rpm));
            }
        }
    }

    @Test
    void deadBandIncludesBothBoundariesEvenWhenStillMoving() {
        for (double rpm : new double[] {-4, -3.9, -0.0, 0, 3.9, 4}) {
            assertTrue(TractionEfficiency.percentage(20, rpm).isEmpty());
            assertEquals("--", TractionEfficiency.format(20, rpm));
        }
    }

    @Test
    void immediatelyAboveThresholdIsDefinedAndNotClamped() {
        assertEquals("400.00%", TractionEfficiency.format(1, 5));
        assertEquals("400.00%", TractionEfficiency.format(-1, -5));
        assertTrue(TractionEfficiency.percentage(1, Math.nextUp(4.0)).orElseThrow() > 100);
    }

    @Test
    void coastingCanExceedOneHundredPercent() {
        assertEquals("200.00%", TractionEfficiency.format(2, 8));
        assertEquals("123.46%", TractionEfficiency.format(1.23456, 8));
    }

    @Test
    void stoppedVehicleHasZeroEfficiencyWhenInputIsAboveThreshold() {
        assertEquals("0.00%", TractionEfficiency.format(0, 64));
        assertEquals("0.00%", TractionEfficiency.format(-0.0, -64));
        assertEquals("--", TractionEfficiency.format(0, 0));
    }

    @Test
    void invalidTelemetryNeverLeaksNanOrInfinityToTheDisplay() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals("--", TractionEfficiency.format(invalid, 64));
            assertEquals("--", TractionEfficiency.format(20, invalid));
        }
        assertEquals("--", TractionEfficiency.format(Double.MAX_VALUE, 5));
    }
}
