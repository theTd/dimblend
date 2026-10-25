package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock.WhistleSize;
import org.junit.jupiter.api.Test;

/**
 * 列车汽笛音高刻度纯数学（2026-10-26 改版）：扳手循环（2.0 后回 0.0）、尺寸档位边界
 * （0.6/0.7、1.3/1.4）、播放音高 2^(v−1)（0/1/2 → 0.5/1.0/2.0）。
 * 刻度以整数十分位存储（0..20），断言全部按整数刻度做，不引入浮点漂移。
 */
class TrainHornPitchTest {

    @Test
    void wrenchCycleAdvancesByOneTenthAndWrapsAtFullScale() {
        assertEquals(11, TrainHorn.cycleTenths(10));
        assertEquals(20, TrainHorn.cycleTenths(19));
        // 用户口径：2.0 之后回到 0.0
        assertEquals(0, TrainHorn.cycleTenths(20));
        assertEquals(1, TrainHorn.cycleTenths(0));
        // 全程单调 +0.1，只有 20 → 0 一个回落点
        for (int tenths = TrainHorn.PITCH_TENTHS_MIN; tenths < TrainHorn.PITCH_TENTHS_MAX; tenths++) {
            assertEquals(tenths + 1, TrainHorn.cycleTenths(tenths), "tenths=" + tenths);
        }
        // 21 次点击回到原值（0..20 共 21 档）
        int tenths = TrainHorn.PITCH_TENTHS_DEFAULT;
        for (int i = 0; i < TrainHorn.PITCH_TENTHS_MAX + 1; i++) {
            tenths = TrainHorn.cycleTenths(tenths);
        }
        assertEquals(TrainHorn.PITCH_TENTHS_DEFAULT, tenths);
    }

    @Test
    void sizeBandsFollowUserThresholdsAtEveryBoundary() {
        // v 0.0–0.6 → SMALL
        for (int tenths = 0; tenths <= 6; tenths++) {
            assertEquals(WhistleSize.SMALL, TrainHorn.sizeForTenths(tenths), "tenths=" + tenths);
        }
        // v 0.7–1.3 → MEDIUM
        for (int tenths = 7; tenths <= 13; tenths++) {
            assertEquals(WhistleSize.MEDIUM, TrainHorn.sizeForTenths(tenths), "tenths=" + tenths);
        }
        // v 1.4–2.0 → LARGE
        for (int tenths = 14; tenths <= 20; tenths++) {
            assertEquals(WhistleSize.LARGE, TrainHorn.sizeForTenths(tenths), "tenths=" + tenths);
        }
        // 两个边界对（0.6/0.7、1.3/1.4）单独钉死，防档位边界漂移
        assertEquals(WhistleSize.SMALL, TrainHorn.sizeForTenths(6));
        assertEquals(WhistleSize.MEDIUM, TrainHorn.sizeForTenths(7));
        assertEquals(WhistleSize.MEDIUM, TrainHorn.sizeForTenths(13));
        assertEquals(WhistleSize.LARGE, TrainHorn.sizeForTenths(14));
        // 进入特殊模式的默认 v = 1.0 落在 MEDIUM
        assertEquals(WhistleSize.MEDIUM, TrainHorn.sizeForTenths(TrainHorn.PITCH_TENTHS_DEFAULT));
    }

    @Test
    void heardPitchIsAnOctaveAroundTheOriginal() {
        assertEquals(0.5F, TrainHorn.heardPitch(0), 1.0E-6F);
        assertEquals(1.0F, TrainHorn.heardPitch(10), 1.0E-6F);
        assertEquals(2.0F, TrainHorn.heardPitch(20), 1.0E-6F);
        // 半程参考点：v=0.5 → 2^-0.5，v=1.5 → 2^0.5
        assertEquals((float) Math.pow(2.0D, -0.5D), TrainHorn.heardPitch(5), 1.0E-6F);
        assertEquals((float) Math.pow(2.0D, 0.5D), TrainHorn.heardPitch(15), 1.0E-6F);
        // 全程单调递增，且落在引擎 clamp [0.5, 2.0] 内（SoundEngine.calculatePitch）
        float previous = Float.NEGATIVE_INFINITY;
        for (int tenths = TrainHorn.PITCH_TENTHS_MIN; tenths <= TrainHorn.PITCH_TENTHS_MAX; tenths++) {
            float pitch = TrainHorn.heardPitch(tenths);
            assertTrue(pitch >= 0.5F && pitch <= 2.0F, "pitch out of clamp at tenths=" + tenths + ": " + pitch);
            assertTrue(pitch > previous, "pitch must rise with tenths at " + tenths + ": " + pitch);
            previous = pitch;
        }
    }

    @Test
    void pitchTextShowsOneDecimalWithoutFloatingPointNoise() {
        assertEquals("0.0", TrainHorn.pitchText(0));
        assertEquals("0.5", TrainHorn.pitchText(5));
        assertEquals("1.0", TrainHorn.pitchText(10));
        assertEquals("1.2", TrainHorn.pitchText(12));
        assertEquals("1.9", TrainHorn.pitchText(19));
        assertEquals("2.0", TrainHorn.pitchText(20));
        for (int tenths = 0; tenths <= 20; tenths++) {
            assertEquals(tenths / 10 + "." + tenths % 10, TrainHorn.pitchText(tenths), "tenths=" + tenths);
            assertEquals(tenths / 10.0D, TrainHorn.pitchValue(tenths), 1.0E-9D, "tenths=" + tenths);
        }
    }

    @Test
    void outOfRangeTenthsAreClampedBeforeAnyMapping() {
        assertEquals(0, TrainHorn.clampTenths(-3));
        assertEquals(20, TrainHorn.clampTenths(31));
        assertEquals(WhistleSize.SMALL, TrainHorn.sizeForTenths(-1));
        assertEquals(WhistleSize.LARGE, TrainHorn.sizeForTenths(99));
        assertEquals(0.5F, TrainHorn.heardPitch(-1), 1.0E-6F);
        assertEquals(2.0F, TrainHorn.heardPitch(99), 1.0E-6F);
        assertEquals(0, TrainHorn.cycleTenths(99));
    }
}
