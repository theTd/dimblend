package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 消失时"半径 5 的怪物死亡粒子"落在哪：用户 2026-11-03 指出上一版是单点爆发，看不出来。
 * 这里把"球内均匀"这个性质钉死，避免又退回单点或只在球壳上撒。
 */
class RewardWagonBurstTest {

    @Test
    void theRadiusAndCountMatchTheRequirement() {
        assertEquals(5.0, RewardWagonBurst.RADIUS, 1.0E-9, "需求写的是半径 5");
        assertEquals(20, RewardWagonBurst.PARTICLES, "原版生物死亡是 20 个 POOF");
    }

    @Test
    void everySampleStaysInsideTheSphere() {
        for (int i = 0; i <= 40; i++) {
            for (int j = 0; j <= 20; j++) {
                for (int k = 0; k <= 20; k++) {
                    RewardWagonBurst.Offset at = RewardWagonBurst.sample(i / 40.0, j / 20.0, k / 20.0);
                    assertTrue(at.length() <= RewardWagonBurst.RADIUS + 1.0E-9,
                            "sample outside the radius: " + at + " length " + at.length());
                }
            }
        }
    }

    @Test
    void theOutermostDrawReachesTheRadius() {
        // radial = 1.0 must put the particle exactly on the sphere, or the burst is too small.
        double max = 0.0;
        for (int j = 0; j <= 20; j++) {
            for (int i = 0; i < 40; i++) {
                max = Math.max(max, RewardWagonBurst.sample(i / 40.0, j / 20.0, 1.0).length());
            }
        }
        assertEquals(RewardWagonBurst.RADIUS, max, 1.0E-9, "the burst must actually span five blocks");
    }

    @Test
    void radialDrawIsCubeRootedSoTheBurstFillsTheBall() {
        // Half the volume is inside r = 5 * cbrt(0.5) ≈ 3.9685. A linear radius would put half
        // the points inside 2.5 and clump the burst at the centre.
        double halfVolumeRadius = RewardWagonBurst.RADIUS * Math.cbrt(0.5);
        assertEquals(halfVolumeRadius, RewardWagonBurst.sample(0.0, 0.5, 0.5).length(), 1.0E-9);
        assertTrue(halfVolumeRadius > 3.9, "half the volume must lie beyond 3.9 blocks");
    }

    @Test
    void everyQuadrantIsReachable() {
        boolean posX = false;
        boolean negX = false;
        boolean posZ = false;
        boolean negZ = false;
        boolean posY = false;
        boolean negY = false;
        for (int j = 0; j <= 20; j++) {
            for (int i = 0; i < 40; i++) {
                RewardWagonBurst.Offset at = RewardWagonBurst.sample(i / 40.0, j / 20.0, 0.8);
                posX |= at.dx() > 0.1;
                negX |= at.dx() < -0.1;
                posZ |= at.dz() > 0.1;
                negZ |= at.dz() < -0.1;
                posY |= at.dy() > 0.1;
                negY |= at.dy() < -0.1;
            }
        }
        assertTrue(posX && negX && posZ && negZ && posY && negY,
                "the burst must surround the point, not favour one side");
    }

    @Test
    void aDegenerateDrawStaysFinite() {
        RewardWagonBurst.Offset at = RewardWagonBurst.sample(0.0, 0.5, 0.0);
        assertEquals(0.0, at.length(), 1.0E-9, "radial 0 is the centre");
        assertTrue(Double.isFinite(RewardWagonBurst.sample(0.25, 0.0, 1.0).dy()),
                "a pole sample must not produce NaN");
    }
}