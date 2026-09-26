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
    void exhaustRunsEightSeconds() {
        assertTrue(SteamEngineOverloadMath.shouldEmitExhaustParticle(0));
        assertFalse(SteamEngineOverloadMath.shouldEmitExhaustParticle(1));
        assertTrue(SteamEngineOverloadMath.shouldEmitExhaustParticle(158));
        assertFalse(SteamEngineOverloadMath.exhaustDone(0));
        assertFalse(SteamEngineOverloadMath.exhaustDone(159));
        assertTrue(SteamEngineOverloadMath.exhaustDone(160));
    }
}
