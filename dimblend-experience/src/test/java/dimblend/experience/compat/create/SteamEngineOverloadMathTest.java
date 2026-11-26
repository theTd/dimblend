package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 蒸汽引擎过载两阶段节拍（纯函数，见 {@link SteamEngineOverloadMath}）。 */
class SteamEngineOverloadMathTest {

    @Test
    void overloadParticleEveryTwoTicks() {
        assertTrue(SteamEngineOverloadMath.shouldEmitOverloadParticle(0));
        assertFalse(SteamEngineOverloadMath.shouldEmitOverloadParticle(1));
        assertTrue(SteamEngineOverloadMath.shouldEmitOverloadParticle(2));
        assertTrue(SteamEngineOverloadMath.shouldEmitOverloadParticle(158));
    }

    @Test
    void overloadSoundEverySecond() {
        assertTrue(SteamEngineOverloadMath.shouldPlayOverloadSound(0));
        assertFalse(SteamEngineOverloadMath.shouldPlayOverloadSound(1));
        assertFalse(SteamEngineOverloadMath.shouldPlayOverloadSound(19));
        assertTrue(SteamEngineOverloadMath.shouldPlayOverloadSound(20));
        assertTrue(SteamEngineOverloadMath.shouldPlayOverloadSound(140));
    }

    @Test
    void windowIsEightSeconds() {
        // 2026-11-26 口径：过载警告窗口 160 tick（8 秒），原 320。
        assertFalse(SteamEngineOverloadMath.windowElapsed(0));
        assertFalse(SteamEngineOverloadMath.windowElapsed(159));
        assertTrue(SteamEngineOverloadMath.windowElapsed(160));
        assertTrue(SteamEngineOverloadMath.windowElapsed(400));
    }

    @Test
    void exhaustRunsThirtyTwoSeconds() {
        assertTrue(SteamEngineOverloadMath.shouldEmitExhaustParticle(0));
        assertFalse(SteamEngineOverloadMath.shouldEmitExhaustParticle(1));
        assertTrue(SteamEngineOverloadMath.shouldEmitExhaustParticle(638));
        assertFalse(SteamEngineOverloadMath.exhaustDone(0));
        assertFalse(SteamEngineOverloadMath.exhaustDone(639));
        assertTrue(SteamEngineOverloadMath.exhaustDone(640));
    }

    @Test
    void exhaustSoundPlaysOnceAtBreak() {
        // 2026-11-26 口径：声音只在断轴当 tick（age == 0）响一次，后面 32 秒只有云粒子。
        assertTrue(SteamEngineOverloadMath.shouldPlayExhaustSound(0));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(1));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(159));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(160));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(320));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(480));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(640));
    }

    @Test
    void remainingTicksRoundTripToAge() {
        // 剩余 640 = 年龄 0；剩余 1 = 年龄 639；清了 = 年龄 640（不再依赖，仅断言夹紧）。
        assertTrue(SteamEngineExhaustAccess.age(640) == 0);
        assertTrue(SteamEngineExhaustAccess.age(1) == 639);
        assertTrue(SteamEngineExhaustAccess.age(0) == 640);
        assertTrue(SteamEngineExhaustAccess.age(-5) == 640);
    }
}
