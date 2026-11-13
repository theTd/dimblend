package dimblend.experience.tuning;

import static org.junit.jupiter.api.Assertions.*;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.LavaMeltMath;
import net.neoforged.neoforge.common.ModConfigSpec;
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
        assertEquals(200.0D, option.defaultValue());
        assertEquals(200, Config.TRAIN_SHAKE_FORCE_PN.getDefault());
        assertEquals(0.0D, option.minimum());
        assertEquals(2000.0D, option.maximum());
        assertTrue(option.isValid(0.0D));
        assertTrue(option.isValid(200.0D));
        assertTrue(option.isValid(2000.0D));
        assertFalse(option.isValid(-1.0D));
        assertFalse(option.isValid(200.1D));
        assertFalse(option.isValid(2001.0D));
        assertFalse(option.isValid(10000.0D));
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
    void brakeSoundSpeedThresholdDefaultsToTwoAndAcceptsTenths() {
        var option = TuningOption.BRAKE_SOUND_SPEED_THRESHOLD;
        assertEquals(2.0D, option.defaultValue());
        for (double valid : new double[] {0.0D, 0.1D, 2.0D, 63.9D, 64.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {-0.1D, 0.01D, 2.05D, 64.1D}) {
            assertFalse(option.isValid(invalid));
        }
    }

    @Test
    void deadmanRadiusAcceptsWholeBlocksBetweenEightAndOneTwentyEight() {
        var option = TuningOption.DEADMAN_RADIUS;
        assertFalse(option.isCheckbox());
        assertSame(TuningOption.Section.MAIN, option.section());
        assertEquals("simurail", option.modId());
        assertEquals(32.0D, option.defaultValue());
        for (double valid : new double[] {8.0D, 32.0D, 64.0D, 128.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {7.0D, 32.5D, 129.0D, 0.0D}) {
            assertFalse(option.isValid(invalid));
        }
        assertEquals("32", option.format(option.defaultValue()));
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
    void theRadioKnobsAreNoLongerPartOfThisPanel() {
        // 2026-11-03: the four radio knobs moved to the jukebox tuning screen in dimblend-radio,
        // because the requirement puts them behind a right-click on the radio itself rather than
        // in a world-wide panel. They must not come back here — a knob offered in two places is a
        // knob with two sources of truth.
        for (String id : new String[] {"radio_static", "radio_end_voice_frequency",
                "radio_end_voice_volume", "radio_acoustic_intensity"}) {
            assertNull(TuningOption.byId(id), "the panel must no longer carry " + id);
        }
    }

    @Test
    void couplerRedstoneDefaultsToForbiddenAndMirrorsTheConfigPolarity() {
        var option = TuningOption.COUPLER_REDSTONE;
        assertTrue(option.isCheckbox());
        assertEquals("coupler_redstone", option.id());
        assertEquals("simurail", option.modId());
        // 用户 2026-11-03 口径（覆盖 2026-10-31 的"允许"）：dbx tune 里列车车钩的红石信号
        // 控制默认"禁止"。面板 0 = 禁止红石断开车钩；同时 Config 默认必须是 true（取反后才等于 0）。
        assertEquals(0.0D, option.defaultValue(), "panel default is 0 = forbidden");
        assertTrue(Config.COUPLER_REDSTONE.getDefault(), "config default true -> panel 0");
        assertEquals(Config.COUPLER_REDSTONE.getDefault() ? 0.0D : 1.0D, option.defaultValue(),
                "panel and config must stay mirror images");
        for (double valid : new double[] {0.0D, 1.0D}) {
            assertTrue(option.isValid(valid));
        }
        assertFalse(option.isValid(0.5D));
        assertEquals("0", option.format(option.defaultValue()));
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
    void scanBudgetsMirrorConfigDefaultsAndAcceptIntegersWithinClampRange() {
        TuningOption[] options = {TuningOption.SABLE_VOID_FIT_SCAN_BUDGET,
                TuningOption.SABLE_LAVA_MELT_SCAN_BUDGET};
        ModConfigSpec.IntValue[] configs = {Config.SABLE_VOID_FIT_SCAN_BUDGET,
                Config.SABLE_LAVA_MELT_SCAN_BUDGET};
        for (int i = 0; i < options.length; i++) {
            var option = options[i];
            assertFalse(option.isCheckbox(), option.id());
            assertSame(TuningOption.Section.MAIN, option.section());
            assertEquals("sable", option.modId());
            // 面板默认值与 Config 扫描预算默认互为镜像（GameTest 钉满的 64 即上限），漂移即失败
            assertEquals((double) configs[i].getDefault(), option.defaultValue(), option.id());
            assertEquals(2.0D, option.defaultValue(), option.id());
            for (double valid : new double[] {1.0D, 2.0D, 64.0D}) {
                assertTrue(option.isValid(valid), option.id());
            }
            for (double invalid : new double[] {0.0D, 1.5D, 64.1D, 65.0D, -1.0D}) {
                assertFalse(option.isValid(invalid), option.id());
            }
            assertEquals("2", option.format(option.defaultValue()), option.id());
        }
    }


    @Test
    void rotatingMonsterDensityIsVanillaPercentageMirroringConfig() {
        var option = TuningOption.ROTATING_MONSTER_CAP;
        assertFalse(option.isCheckbox());
        assertSame(TuningOption.Section.MAIN, option.section());
        assertEquals("", option.modId());
        // 面板默认值与 Config.ROTATING_MONSTER_DENSITY 默认值 ×100 互为镜像，漂移即失败
        assertEquals(Config.ROTATING_MONSTER_DENSITY.getDefault() * 100.0D, option.defaultValue());
        assertEquals(50.0D, option.defaultValue());
        for (double valid : new double[] {0.0D, 1.0D, 50.0D, 100.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {-1.0D, 0.5D, 99.5D, 100.5D, 101.0D}) {
            assertFalse(option.isValid(invalid));
        }
        assertEquals("50", option.format(option.defaultValue()));
    }

    @Test
    void itemDrainGrowthCostMirrorsConfigDefaultAndAcceptsWholeMb() {
        var option = TuningOption.ITEM_DRAIN_GROWTH_COST;
        assertFalse(option.isCheckbox());
        assertSame(TuningOption.Section.MAIN, option.section());
        assertEquals("create", option.modId());
        // 面板默认值与 Config.ITEM_DRAIN_GROWTH_COST_MB 默认耗水互为镜像，漂移即失败
        assertEquals((double) Config.ITEM_DRAIN_GROWTH_COST_MB.getDefault(), option.defaultValue());
        assertEquals(20.0D, option.defaultValue());
        for (double valid : new double[] {0.0D, 20.0D, 1500.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {-1.0D, 10.5D, 1500.5D, 1501.0D}) {
            assertFalse(option.isValid(invalid));
        }
        assertEquals("20", option.format(option.defaultValue()));
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
    void observerBlazeRadiusMirrorsConfigDefaultAndAcceptsWholeBlocksInRange() {
        var option = TuningOption.OBSERVER_BLAZE_RADIUS;
        assertFalse(option.isCheckbox());
        assertSame(TuningOption.Section.SABLE, option.section());
        assertEquals("sable", option.modId());
        // 面板默认值与 Config.OBSERVER_BLAZE_RADIUS 默认探测半径互为镜像，漂移即失败
        assertEquals((double) Config.OBSERVER_BLAZE_RADIUS.getDefault(), option.defaultValue());
        assertEquals(32.0D, option.defaultValue());
        assertEquals(16.0D, option.minimum());
        assertEquals(64.0D, option.maximum());
        assertEquals(1.0D, option.step());
        for (double valid : new double[] {16.0D, 17.0D, 32.0D, 63.0D, 64.0D}) {
            assertTrue(option.isValid(valid));
        }
        for (double invalid : new double[] {8.0D, 15.0D, 16.5D, 32.5D, 64.1D, 65.0D, -1.0D}) {
            assertFalse(option.isValid(invalid));
        }
        assertEquals("32", option.format(option.defaultValue()));
        // experience owns the row even though it is listed on the Sable page: it must not be routed into
        // Sable's physics config (which throws for it), and the headlight predicate stays about headlights.
        assertTrue(option.experienceOwned());
        assertFalse(option.sable());
        assertFalse(option.headlight());
    }

    @Test
    void formattingKeepsDeclaredPrecisionAndUsesDecimalPoint() {
        assertEquals("2.0", TuningOption.SHAKE_INTERVAL.format(2.0D));
        assertEquals("1200", TuningOption.SHAKE_FORCE.format(1200.0D));
        assertEquals("0.50", TuningOption.SOILING_MULTIPLIER.format(0.5D));
        assertEquals("1.00", TuningOption.EXP_CLEAR_RATIO.format(1.0D));
    }
}
