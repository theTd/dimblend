package dimblend.experience.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** z256 进度条纯函数：进度 = (|z| − 256×层级) / 256，负数 0%，|z|≤128 清零。 */
class ZProgressMathTest {

    @Test
    void tierZeroIsPlainRatio() {
        assertEquals(16.0 / 256.0, ZProgressMath.progressFor(16, 0), 1e-6);
        assertEquals(200.0 / 256.0, ZProgressMath.progressFor(200, 0), 1e-6);
    }

    @Test
    void eachTierSubtractsAnotherSegment() {
        assertEquals(44.0 / 256.0, ZProgressMath.progressFor(300, 1), 1e-6);
        assertEquals(88.0 / 256.0, ZProgressMath.progressFor(600, 2), 1e-6);
        assertEquals(0.0, ZProgressMath.progressFor(256, 1), 1e-6);
    }

    @Test
    void behindReachedBoundaryShowsZero() {
        // 层级 2（已过 512）退回 400 / 300：负数 → 0%，与走向无关
        assertEquals(0.0, ZProgressMath.progressFor(400, 2), 1e-6);
        assertEquals(0.0, ZProgressMath.progressFor(300, 2), 1e-6);
        // 层级 1 退回僵持区 200
        assertEquals(0.0, ZProgressMath.progressFor(200, 1), 1e-6);
    }

    @Test
    void withinResetRadiusIgnoresTier() {
        assertEquals(0.5, ZProgressMath.progressFor(128, 3), 1e-6);
        assertEquals(100.0 / 256.0, ZProgressMath.progressFor(100, 1), 1e-6);
        // 129 已出清零半径：仍按层级算
        assertEquals(0.0, ZProgressMath.progressFor(129, 1), 1e-6);
    }

    @Test
    void unsyncedTierClampsToFull() {
        // 越过 256、服务端层级 +1 尚未同步到：钳到满条
        assertEquals(1.0, ZProgressMath.progressFor(260, 0), 1e-6);
    }
}
