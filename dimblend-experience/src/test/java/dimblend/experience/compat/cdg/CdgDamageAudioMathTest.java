package dimblend.experience.compat.cdg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** 损坏期灭火音参数（纯函数，见 {@link CdgDamageAudioMath}）。 */
class CdgDamageAudioMathTest {

    @Test
    void intervalStaysBetweenThreeAndSixSeconds() {
        Random random = new Random(20261126L);
        for (int i = 0; i < 2000; i++) {
            int ticks = CdgDamageAudioMath.nextIntervalTicks(random.nextFloat());
            assertTrue(ticks >= CdgDamageAudioMath.MIN_INTERVAL_TICKS,
                    "间隔过短：" + ticks);
            assertTrue(ticks <= CdgDamageAudioMath.MAX_INTERVAL_TICKS,
                    "间隔过长：" + ticks);
        }
    }

    @Test
    void intervalEndpointsAreClamped() {
        // roll 取端点/越界都必须落在 [60,120]，不许溢出到窗口外侧
        assertEquals(CdgDamageAudioMath.MIN_INTERVAL_TICKS,
                CdgDamageAudioMath.nextIntervalTicks(0.0F));
        assertEquals(CdgDamageAudioMath.MAX_INTERVAL_TICKS,
                CdgDamageAudioMath.nextIntervalTicks(1.0F));
        assertEquals(CdgDamageAudioMath.MIN_INTERVAL_TICKS,
                CdgDamageAudioMath.nextIntervalTicks(-3.0F));
        assertEquals(CdgDamageAudioMath.MAX_INTERVAL_TICKS,
                CdgDamageAudioMath.nextIntervalTicks(9.0F));
    }

    @Test
    void intervalGrowsWithRoll() {
        int low = CdgDamageAudioMath.nextIntervalTicks(0.01F);
        int mid = CdgDamageAudioMath.nextIntervalTicks(0.5F);
        int high = CdgDamageAudioMath.nextIntervalTicks(0.99F);
        assertTrue(low < mid, low + " !< " + mid);
        assertTrue(mid < high, mid + " !< " + high);
    }

    @Test
    void volumeIsQuiet() {
        Random random = new Random(7L);
        for (int i = 0; i < 500; i++) {
            float volume = CdgDamageAudioMath.volume(random.nextFloat());
            assertTrue(volume >= CdgDamageAudioMath.MIN_VOLUME - 1.0E-6F, "音量过小：" + volume);
            assertTrue(volume <= CdgDamageAudioMath.MAX_VOLUME + 1.0E-6F, "音量过大：" + volume);
        }
    }

    @Test
    void pitchVariesAroundNatural() {
        assertTrue(CdgDamageAudioMath.pitch(0.0F) < 1.0F);
        assertTrue(CdgDamageAudioMath.pitch(1.0F) > 1.0F);
        assertEquals(CdgDamageAudioMath.MIN_PITCH, CdgDamageAudioMath.pitch(0.0F), 1.0E-6F);
        assertEquals(CdgDamageAudioMath.MAX_PITCH, CdgDamageAudioMath.pitch(1.0F), 1.0E-6F);
    }

    @Test
    void cooldownCountsDownAndStopsAtZero() {
        assertEquals(4, CdgDamageAudioMath.tickDown(5));
        assertEquals(1, CdgDamageAudioMath.tickDown(2));
        assertEquals(0, CdgDamageAudioMath.tickDown(1));
        assertEquals(0, CdgDamageAudioMath.tickDown(0));   // 不清成负数
        assertEquals(0, CdgDamageAudioMath.tickDown(-7));  // 脏值也夹紧
    }
}
