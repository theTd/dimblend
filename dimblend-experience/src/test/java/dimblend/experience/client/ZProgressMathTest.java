package dimblend.experience.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** z256 进度条纯函数（实测修订：回程瞬时归零，无锁存记忆）。 */
class ZProgressMathTest {

    @Test
    void forwardShowsSegmentFormula() {
        assertEquals(16.0 / 256.0, ZProgressMath.progressFor(16, false), 1e-6);
        assertEquals(44.0 / 256.0, ZProgressMath.progressFor(300, false), 1e-6);
        assertEquals(0.0, ZProgressMath.progressFor(256, false), 1e-6);
    }

    @Test
    void returningInBackHalfShowsZeroInstantly() {
        assertEquals(0.0, ZProgressMath.progressFor(200, true), 1e-6);
        assertEquals(0.0, ZProgressMath.progressFor(500, true), 1e-6);
    }

    @Test
    void returningInFrontHalfStillShowsFormula() {
        assertEquals(44.0 / 256.0, ZProgressMath.progressFor(300, true), 1e-6);
    }

    @Test
    void turningForwardResumesImmediately() {
        // 无锁存记忆：同一位置去程即按公式，不再保持 0%
        assertEquals(190.0 / 256.0, ZProgressMath.progressFor(190, false), 1e-6);
    }

    @Test
    void exactHalfBoundaryShowsFiftyPercent() {
        assertEquals(0.5, ZProgressMath.progressFor(128, true), 1e-6);
        assertEquals(0.5, ZProgressMath.progressFor(128, false), 1e-6);
    }
}
