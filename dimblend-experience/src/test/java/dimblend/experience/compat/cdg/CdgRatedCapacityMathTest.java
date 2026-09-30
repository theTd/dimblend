package dimblend.experience.compat.cdg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** B7 应力容量恒按额定（见 {@link CdgRatedCapacityMath}）：柴油 1.3.15 数据 96rpm/6144SU、巨型 224rpm/16384SU。 */
class CdgRatedCapacityMathTest {

    private static final float TOTAL_SU = 6144.0F;
    private static final float RATED = 96.0F;

    /** 普通/组合式按 CDG 公式算出的网络容量：总 SU / 分母 × |产出转速|。 */
    private static float networkCapacity(float generatedSpeed) {
        float divisor = CdgRatedCapacityMath.capacityDivisor(Math.max(0.01F, RATED), generatedSpeed);
        return TOTAL_SU / divisor * Math.abs(generatedSpeed);
    }

    @Test
    void rampAndFluctuationKeepRatedCapacity() {
        assertEquals(TOTAL_SU, networkCapacity(16.0F), 1.0E-2F);
        assertEquals(TOTAL_SU, networkCapacity(RATED * 0.8F), 1.0E-2F);
        assertEquals(TOTAL_SU, networkCapacity(-RATED), 1.0E-2F);
    }

    @Test
    void stoppedOrInvalidKeepsCdgDivisor() {
        assertEquals(RATED, CdgRatedCapacityMath.capacityDivisor(RATED, 0.0F));
        assertEquals(RATED, CdgRatedCapacityMath.capacityDivisor(RATED, Float.NaN));
    }

    @Test
    void tinySpeedUsesCdgFloor() {
        assertEquals(CdgRatedCapacityMath.MIN_DIVISOR, CdgRatedCapacityMath.capacityDivisor(RATED, 0.001F));
    }

    @Test
    void hugeEngineContributesRatedWhenShaftIsSlower() {
        float perRpm = 16384.0F / 224.0F;
        float scaled = CdgRatedCapacityMath.shaftCapacityPerRpm(perRpm, 224.0F, 16.0F);
        assertEquals(16384.0F, scaled * 16.0F, 1.0E-1F);
    }

    @Test
    void hugeEngineKeepsVanillaWhenShaftIsFaster() {
        float perRpm = 50.0F;
        // 混烧：另一台把轴带到 256，本机额定 224——保留原版 每转容量 × 轴转速
        assertEquals(perRpm, CdgRatedCapacityMath.shaftCapacityPerRpm(perRpm, 224.0F, 256.0F));
        assertEquals(perRpm, CdgRatedCapacityMath.shaftCapacityPerRpm(perRpm, 224.0F, 224.0F));
    }

    @Test
    void hugeEngineIgnoresNonPositiveInputs() {
        assertEquals(50.0F, CdgRatedCapacityMath.shaftCapacityPerRpm(50.0F, 224.0F, 0.0F));
        assertEquals(50.0F, CdgRatedCapacityMath.shaftCapacityPerRpm(50.0F, 0.0F, 16.0F));
        assertEquals(50.0F, CdgRatedCapacityMath.shaftCapacityPerRpm(50.0F, 224.0F, Float.NaN));
    }
}
