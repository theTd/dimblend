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
        assertTrue(SteamEngineOverloadMath.shouldEmitOverloadParticle(318));
    }

    @Test
    void overloadSoundEverySecond() {
        assertTrue(SteamEngineOverloadMath.shouldPlayOverloadSound(0));
        assertFalse(SteamEngineOverloadMath.shouldPlayOverloadSound(1));
        assertFalse(SteamEngineOverloadMath.shouldPlayOverloadSound(19));
        assertTrue(SteamEngineOverloadMath.shouldPlayOverloadSound(20));
        assertTrue(SteamEngineOverloadMath.shouldPlayOverloadSound(300));
    }

    @Test
    void windowIsSixteenSeconds() {
        assertFalse(SteamEngineOverloadMath.windowElapsed(0));
        assertFalse(SteamEngineOverloadMath.windowElapsed(319));
        assertTrue(SteamEngineOverloadMath.windowElapsed(320));
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
    void exhaustSoundReplaysFourTimesAcrossThirtyTwoSeconds() {
        // 0 / 160 / 320 / 480 各一次，480 是第 25 秒，尾巴放到 640 刚好收尾。
        assertTrue(SteamEngineOverloadMath.shouldPlayExhaustSound(0));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(1));
        assertFalse(SteamEngineOverloadMath.shouldPlayExhaustSound(159));
        assertTrue(SteamEngineOverloadMath.shouldPlayExhaustSound(160));
        assertTrue(SteamEngineOverloadMath.shouldPlayExhaustSound(320));
        assertTrue(SteamEngineOverloadMath.shouldPlayExhaustSound(480));
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
