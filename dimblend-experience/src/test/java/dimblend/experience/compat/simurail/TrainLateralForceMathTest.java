package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** E8 车架随机横向力：概率、间隔与冲量（纯函数，见 {@link TrainLateralForceMath}）。 */
class TrainLateralForceMathTest {

    @Test
    void chanceIsSpeedOverTwentyClampedToOne() {
        assertEquals(0.25D, TrainLateralForceMath.triggerChance(5.0D), 1.0E-9);
        assertEquals(0.5D, TrainLateralForceMath.triggerChance(10.0D), 1.0E-9);
        assertEquals(1.0D, TrainLateralForceMath.triggerChance(20.0D), 1.0E-9);
        assertEquals(1.0D, TrainLateralForceMath.triggerChance(35.0D), 1.0E-9);
        assertEquals(0.0D, TrainLateralForceMath.triggerChance(0.0D), 1.0E-9);
    }

    @Test
    void triggerUsesStrictRollBelowChance() {
        assertTrue(TrainLateralForceMath.shouldTrigger(10.0D, 0.49D));
        assertFalse(TrainLateralForceMath.shouldTrigger(10.0D, 0.5D));
        // 20 m/s 及以上必中（roll 恒 < 1）
        assertTrue(TrainLateralForceMath.shouldTrigger(20.0D, 0.999999D));
    }

    @Test
    void intervalIsFixedTwoSeconds() {
        assertEquals(40, TrainLateralForceMath.INTERVAL_TICKS);
    }

    @Test
    void totalImpulseOverPushIsIndependentOfSubsteps() {
        // 10 tick = 0.5 秒，总冲量 1200 × 0.5 = 600，与每 tick 物理子步数无关
        for (int substeps : new int[] {1, 2, 4}) {
            double step = 0.05D / substeps;
            double total = 0.0D;
            for (int i = 0; i < TrainLateralForceMath.PUSH_DURATION_TICKS * substeps; i++) {
                total += TrainLateralForceMath.impulsePerStep(step);
            }
            assertEquals(600.0D, total, 1.0E-6);
        }
    }
}
