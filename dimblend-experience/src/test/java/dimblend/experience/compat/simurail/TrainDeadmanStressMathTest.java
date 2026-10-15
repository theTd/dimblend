package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** E7' 锁存强制过载反算（见 {@link TrainDeadmanStressMath}）：需求恒超容量（纯函数，零依赖）。 */
class TrainDeadmanStressMathTest {

    @Test
    void demandAlwaysExceedsCapacityForAnyNonzeroSpeed() {
        // 任意 |speed|>=1：demand = impact × |speed| = capacity × 1.5 + 1 > capacity
        for (float capacity : new float[]{0.0F, 1.0F, 1024.0F, 590000.0F}) {
            for (float speed : new float[]{1.0F, 16.0F, -64.0F, 256.0F, -256.0F}) {
                float impact = TrainDeadmanStressMath.forcedImpact(capacity, speed);
                float demand = impact * Math.abs(speed);
                assertTrue(demand > capacity,
                        "capacity=" + capacity + " speed=" + speed + " demand=" + demand);
            }
        }
    }

    @Test
    void zeroSpeedClampsToOneAndOverloadsOnceSpinning() {
        // 锁存时网络停转（speed=0）：按 1 倍转速档反算，不除爆；
        // 一旦启动（|speed|>=1）需求即 = 容量 × 1.5 + 1，立即过载
        float impact = TrainDeadmanStressMath.forcedImpact(1024.0F, 0.0F);
        assertEquals(1024.0F * 1.5F + 1.0F, impact, 0.001F);
        assertTrue(impact * 1.0F > 1024.0F);
        assertTrue(impact * 256.0F > 1024.0F);
    }

    @Test
    void zeroCapacityNetworkStillOverloads() {
        // 零容量网络（无源/空账）：+1 兜底保证需求 > 0
        float impact = TrainDeadmanStressMath.forcedImpact(0.0F, 128.0F);
        assertTrue(impact * 128.0F > 0.0F);
    }

    @Test
    void typicalDieselNetworkOverloadRatioIsConstantAcrossSpeeds() {
        // 典型柴油机网络 capacity≈59 万 su：需求/容量 ≈ 1.5，且跨转速恒定
        float capacity = 590000.0F;
        for (float speed : new float[]{1.0F, 64.0F, 256.0F}) {
            float demand = TrainDeadmanStressMath.forcedImpact(capacity, speed) * Math.abs(speed);
            assertEquals(1.5F, demand / capacity, 0.0001F, "speed=" + speed);
        }
    }

    @Test
    void fallbackImpactIsWindowOriginal65536() {
        assertEquals(65536.0F, TrainDeadmanStressMath.FALLBACK_IMPACT, 0.0F);
    }
}
