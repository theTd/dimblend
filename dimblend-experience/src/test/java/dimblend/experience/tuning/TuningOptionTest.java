package dimblend.experience.tuning;

import static org.junit.jupiter.api.Assertions.*;
import dimblend.experience.compat.sable.LavaMeltMath;
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
    void brakeSignalCapAcceptsWholeLevelsBetweenOneAndFifteenOnly() {
        var option = TuningOption.BRAKE_SIGNAL_CAP;
        for (double valid : new double[] {1.0D, 4.0D, 15.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {0.0D, 0.5D, 4.5D, 15.5D, 16.0D}) {
            assertFalse(option.isValid(invalid));
        }
    }

    @Test
    void bogieRollTorqueMirrorsSimurailDefaultAndTakesWholeValuesUpToTenMillion() {
        var option = TuningOption.BOGIE_ROLL_TORQUE;
        assertFalse(option.isCheckbox());
        assertSame(TuningOption.Section.MAIN, option.section());
        assertEquals("simurail", option.modId());
        assertEquals(5000.0D, option.defaultValue());
        for (double valid : new double[] {0.0D, 1.0D, 5000.0D, 50000.0D, 10000000.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {-1.0D, 0.5D, 5000.5D, 10000001.0D}) {
            assertFalse(option.isValid(invalid));
        }
        // The panel's input box holds at most 12 characters.
        assertEquals("10000000", option.format(option.maximum()));
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
    void radioNoiseVolumeAcceptsHundredthsAroundTheFormerOnLevel() {
        var option = TuningOption.RADIO_STATIC;
        assertFalse(option.isCheckbox());
        assertEquals(0.5D, option.defaultValue());
        for (int i = 0; i <= 200; i++) {
            assertTrue(option.isValid(i / 100.0D));
        }
        assertFalse(option.isValid(0.005D));
        assertFalse(option.isValid(2.01D));
        assertEquals("0.50", option.format(option.defaultValue()));
    }

    @Test
    void radioEndVoiceFrequencyDefaultsToDoubleAndAcceptsTenths() {
        var option = TuningOption.RADIO_END_VOICE_FREQUENCY;
        assertTrue(option.radio());
        assertEquals(2.0D, option.defaultValue());
        for (double valid : new double[] {0.0D, 0.1D, 1.0D, 2.0D, 3.9D, 4.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {-0.1D, 0.01D, 2.05D, 4.1D}) {
            assertFalse(option.isValid(invalid));
        }
        assertEquals("2.0", option.format(option.defaultValue()));
    }

    @Test
    void sableSubstepsRejectsZeroFractionsAndValuesAboveTen() {
        var option = TuningOption.SABLE_SUBSTEPS;
        assertFalse(option.isCheckbox());
        assertSame(TuningOption.Section.SABLE, option.section());
        assertSame(TuningOption.Section.MAIN, TuningOption.SHAKE_INTERVAL.section());
        assertEquals(2.0D, option.defaultValue());
        for (int i = 1; i <= 10; i++) {
            assertTrue(option.isValid(i));
        }
        assertFalse(option.isValid(0.0D));
        assertFalse(option.isValid(11.0D));
        assertFalse(option.isValid(2.5D));
        assertEquals("2", option.format(option.defaultValue()));
    }

    @Test
    void sableIterationAndIntervalOptionsRespectTheirCeilings() {
        TuningOption[] options = {TuningOption.SABLE_SOLVER_ITERATIONS, TuningOption.SABLE_PGS_ITERATIONS,
                TuningOption.SABLE_STABILIZATION_ITERATIONS, TuningOption.SABLE_PHYSICS_TICK_INTERVAL};
        int[] ceilings = {64, 8, 8, 10};
        for (int i = 0; i < options.length; i++) {
            assertTrue(options[i].isValid(1.0D), options[i].id());
            assertTrue(options[i].isValid(ceilings[i]), options[i].id());
            assertFalse(options[i].isValid(0.0D), options[i].id());
            assertFalse(options[i].isValid(ceilings[i] + 1.0D), options[i].id());
            assertFalse(options[i].isValid(1.5D), options[i].id());
        }
    }

    @Test
    void lavaMeltSpeedAcceptsTenthsBetweenHalfAndSixtyFour() {
        var option = TuningOption.SABLE_LAVA_MELT_SPEED;
        assertFalse(option.isCheckbox());
        assertSame(TuningOption.Section.MAIN, option.section());
        assertEquals("sable", option.modId());
        // 面板默认值与熔毁数学的默认倍率互为镜像（Config 默认值亦取自该常量），漂移即失败
        assertEquals(LavaMeltMath.DEFAULT_SPEED_MULTIPLIER, option.defaultValue());
        assertEquals(4.0D, option.defaultValue());
        for (double valid : new double[] {0.5D, 0.6D, 4.0D, 63.9D, 64.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {0.0D, 0.4D, 4.05D, 64.1D, -0.5D}) {
            assertFalse(option.isValid(invalid));
        }
        assertEquals("4.0", option.format(option.defaultValue()));
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
