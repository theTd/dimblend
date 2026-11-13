package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** E8 车架随机横向力：概率、间隔、质量归一化与水平蛇行回中弹簧（纯函数，见 {@link TrainLateralForceMath}）。 */
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
    void intervalSupportsDisabledValueAndTenthsOfSeconds() {
        assertEquals(0, TrainLateralForceMath.intervalTicks(0.0D));
        assertEquals(2, TrainLateralForceMath.intervalTicks(0.1D));
        assertEquals(40, TrainLateralForceMath.intervalTicks(2.0D));
        assertEquals(1200, TrainLateralForceMath.intervalTicks(60.0D));
    }

    @Test
    void customForceScalesImpulseAndZeroForceHasNoImpulse() {
        assertEquals(0.0D, TrainLateralForceMath.impulsePerStep(0.05D, 0.0D));
        assertEquals(500.0D, TrainLateralForceMath.impulsePerStep(0.05D, 10000.0D));
        assertEquals(0.0D, TrainLateralForceMath.impulsePerStep(0.05D, 0.0D, 80_000.0D));
    }

    @Test
    void massNormalizedForceKeepsConsistentAccelerationAcrossLightAndHeavyCars() {
        // 轻车不低于配置原值
        assertEquals(1200.0D, TrainLateralForceMath.massNormalizedForcePn(1200.0D, 250.0D), 1.0E-9);
        // 40,000 kg 法向等效质量（约 80t 标准车厢单端）：1200 * (40000 / 400) = 120,000 N -> 3.0 m/s²
        double heavyForce = TrainLateralForceMath.massNormalizedForcePn(1200.0D, 40_000.0D);
        assertEquals(120_000.0D, heavyForce, 1.0E-6);
        assertEquals(3.0D, heavyForce / 40_000.0D, 1.0E-9);
    }

    @Test
    void swaySpringRestoresCarTowardTrackCenterlineAndRespectsSwayWindow() {
        // 车体向 +lateral 偏移 0.08m 时，localPivotOffset.dot(lateral) = -0.08m，回中力必须为负（拉回 0）
        double restoring = TrainLateralForceMath.swaySpringForce(-0.08D, 0.2D, 40_000.0D, 2);
        assertTrue(restoring < 0.0D);

        assertTrue(TrainLateralForceMath.canPushFurther(-0.08D, 1.0D));
        assertFalse(TrainLateralForceMath.canPushFurther(-0.12D, 1.0D));
        assertTrue(TrainLateralForceMath.canPushFurther(-0.12D, -1.0D));
    }

    @Test
    void microRollTorqueNormalizesAcrossLightAndHeavyMomentsAndRespectsTwoPointFiveDegreeCap() {
        assertEquals(2.5D, TrainLateralForceMath.MAX_MICRO_ROLL_DEGREES, 1.0E-9);
        assertEquals(Math.toRadians(2.5D), TrainLateralForceMath.MAX_MICRO_ROLL_RADIANS, 1.0E-9);

        // 底部向 +lateral 推时车顶向 -lateral 倾（绕 +direction 为负）
        assertEquals(-1.0D, TrainLateralForceMath.rollSignForLateralPush(1.0D), 1.0E-9);
        assertEquals(1.0D, TrainLateralForceMath.rollSignForLateralPush(-1.0D), 1.0E-9);
        assertEquals(0.0D, TrainLateralForceMath.rollSignForLateralPush(0.0D), 1.0E-9);

        // 轻车（100 kg·m²）与重车（50,000 kg·m²）在默认 1200 pN 下获得一致的 2.4 rad/s² 单转向架角加速度
        double lightTorque = TrainLateralForceMath.inertiaNormalizedRollTorque(1200.0D, 100.0D, 1);
        double heavyTorque = TrainLateralForceMath.inertiaNormalizedRollTorque(1200.0D, 50_000.0D, 1);
        assertEquals(2.4D, lightTorque / 100.0D, 1.0E-9);
        assertEquals(2.4D, heavyTorque / 50_000.0D, 1.0E-9);
        assertEquals(0.0D, TrainLateralForceMath.inertiaNormalizedRollTorque(0.0D, 50_000.0D, 2), 1.0E-9);

        // 多转向架按 sqrt(N) 分摊随机扭矩方差
        double sixBogeys = TrainLateralForceMath.inertiaNormalizedRollTorque(1200.0D, 500.0D, 6);
        assertEquals(1200.0D / Math.sqrt(6.0D), sixBogeys, 1.0E-9);

        // 严格钳制在 ±2.5° 以内停止继续同向施矩，反向回正始终允许
        assertTrue(TrainLateralForceMath.canRollFurther(Math.toRadians(2.4D), 1.0D));
        assertFalse(TrainLateralForceMath.canRollFurther(Math.toRadians(2.5D), 1.0D));
        assertTrue(TrainLateralForceMath.canRollFurther(Math.toRadians(2.5D), -1.0D));
        assertFalse(TrainLateralForceMath.canRollFurther(Math.toRadians(-2.5D), -1.0D));
    }

    @Test
    void totalImpulseOverPushIsIndependentOfSubsteps() {
        // 默认 200 pN，持续 10 tick = 0.5 秒，总冲量 200 × 0.5 = 100，与每 tick 物理子步数无关
        assertEquals(200.0D, TrainLateralForceMath.FORCE_PN, 1.0E-9);
        for (int substeps : new int[] {1, 2, 4}) {
            double step = 0.05D / substeps;
            double total = 0.0D;
            for (int i = 0; i < TrainLateralForceMath.PUSH_DURATION_TICKS * substeps; i++) {
                total += TrainLateralForceMath.impulsePerStep(step);
            }
            assertEquals(100.0D, total, 1.0E-6);
        }
    }

    @Test
    void subLevelDrawPicksSingleFastBogeyPerIntervalAndResetsWhenSlowOrReconfigured() {
        var state = new TrainLateralForceMath.SubLevelDrawState();
        double[] sixFast = {12.0D, 12.0D, 12.0D, 12.0D, 12.0D, 12.0D};
        AtomicInteger selectorCalls = new AtomicInteger();
        AtomicInteger rollCalls = new AtomicInteger();
        List<Integer> observedBounds = new ArrayList<>();

        for (int tick = 1; tick < 40; tick++) {
            assertNull(TrainLateralForceMath.stepSubLevelDraw(
                    state,
                    40,
                    sixFast,
                    bound -> {
                        selectorCalls.incrementAndGet();
                        return 0;
                    },
                    () -> {
                        rollCalls.incrementAndGet();
                        return 0.0D;
                    },
                    () -> true));
        }
        assertEquals(0, selectorCalls.get());
        assertEquals(0, rollCalls.get());

        // 第 40 tick 仅从 6 个车架中随机挑 1 个并抽签 1 次
        TrainLateralForceMath.DrawOutcome hit = TrainLateralForceMath.stepSubLevelDraw(
                state,
                40,
                sixFast,
                bound -> {
                    selectorCalls.incrementAndGet();
                    observedBounds.add(bound);
                    return 4;
                },
                () -> {
                    rollCalls.incrementAndGet();
                    return 0.2D;
                },
                () -> false);
        assertNotNull(hit);
        assertEquals(4, hit.candidateIndex());
        assertEquals(-1.0D, hit.sign(), 1.0E-9);
        assertEquals(1, selectorCalls.get());
        assertEquals(1, rollCalls.get());
        assertEquals(List.of(6), observedBounds);
        assertEquals(40, state.countdown());

        // 混合速度：<= 4 m/s 的车架不参与抽选，仅从 2 个高速车架（原下标 1 与 3）中挑 1 个并用其速度判定概率
        double[] mixed = {2.0D, 10.0D, 4.0D, 16.0D};
        var mixedState = new TrainLateralForceMath.SubLevelDrawState();
        TrainLateralForceMath.DrawOutcome miss = TrainLateralForceMath.stepSubLevelDraw(
                mixedState,
                1,
                mixed,
                bound -> {
                    assertEquals(2, bound);
                    return 0; // 第 0 个高速车架即 mixed[1]（10 m/s -> 0.5 概率）
                },
                () -> 0.6D, // < 0.8（mixed[3]），但 >= 0.5（mixed[1]），按选中的 mixed[1] 不命中
                () -> true);
        assertNull(miss);

        TrainLateralForceMath.DrawOutcome fastPick = TrainLateralForceMath.stepSubLevelDraw(
                mixedState,
                1,
                mixed,
                bound -> {
                    assertEquals(2, bound);
                    return 1; // 第 1 个高速车架即 mixed[3]（16 m/s -> 0.8 概率）
                },
                () -> 0.6D,
                () -> true);
        assertNotNull(fastPick);
        assertEquals(3, fastPick.candidateIndex());
        assertEquals(1.0D, fastPick.sign(), 1.0E-9);

        // 减速至 <= 4 m/s 或间隔配置改变时清空倒计时，恢复后重新计满完整间隔
        TrainLateralForceMath.stepSubLevelDraw(state, 40, new double[] {4.0D, 3.0D}, b -> 0, () -> 0.0D, () -> true);
        assertEquals(-1, state.countdown());
        assertNull(TrainLateralForceMath.stepSubLevelDraw(state, 5, sixFast, b -> 0, () -> 0.0D, () -> true));
        assertEquals(4, state.countdown());
        assertNull(TrainLateralForceMath.stepSubLevelDraw(state, 10, sixFast, b -> 0, () -> 0.0D, () -> true));
        assertEquals(9, state.countdown());
    }
}
