package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** E7' 编组聚类与距离值守判定、解除脉冲状态机（纯函数，见 {@link TrainDeadmanMath}）。 */
class TrainDeadmanMathTest {

    private static final double RADIUS_SQ = 32.0D * 32.0D;

    private static List<TrainDeadmanMath.Probe> probes(double... xyzSpeed) {
        java.util.ArrayList<TrainDeadmanMath.Probe> list = new java.util.ArrayList<>();
        for (int i = 0; i < xyzSpeed.length; i += 4) {
            list.add(new TrainDeadmanMath.Probe(xyzSpeed[i], xyzSpeed[i + 1], xyzSpeed[i + 2], xyzSpeed[i + 3]));
        }
        return list;
    }

    @Test
    void chainDistanceLinksCoupledBogeysIntoOneConsist() {
        // 链距内（24 格）传递闭包：a—b—c 连成一组，d 超链距独立
        List<TrainDeadmanMath.Probe> all = probes(
                0, 64, 0, 10,
                24, 64, 0, 10,
                48, 64, 0, 10,
                100, 64, 0, 10);
        List<List<Integer>> groups = TrainDeadmanMath.cluster(all);
        assertEquals(2, groups.size());
        List<Integer> merged = groups.stream().filter(g -> g.size() == 3).findFirst().orElseThrow();
        assertTrue(merged.containsAll(List.of(0, 1, 2)));
        List<Integer> alone = groups.stream().filter(g -> g.size() == 1).findFirst().orElseThrow();
        assertEquals(List.of(3), alone);
    }

    @Test
    void clusterBoundaryIsInclusive() {
        // 恰为链距的算同一编组，超出即分离
        List<TrainDeadmanMath.Probe> edge = probes(
                0, 64, 0, 0,
                TrainDeadmanMath.COUPLING_CHAIN, 64, 0, 0);
        assertEquals(1, TrainDeadmanMath.cluster(edge).size());
        List<TrainDeadmanMath.Probe> apart = probes(
                0, 64, 0, 0,
                TrainDeadmanMath.COUPLING_CHAIN + 0.01D, 64, 0, 0);
        assertEquals(2, TrainDeadmanMath.cluster(apart).size());
    }

    @Test
    void nearestBogeyDistanceIgnoresItsSpeed() {
        // 玩家在编组旁（距离 5 格），最近架未超速（3.9 m/s）而编组内另一架超速：
        // 距离判定不看最近架速度——玩家算已值守，修复 2026-10-15 实测误刹根因
        List<TrainDeadmanMath.Probe> consist = probes(
                0, 64, 0, 3.9D,
                20, 64, 0, 10);
        List<List<Integer>> groups = TrainDeadmanMath.cluster(consist);
        assertEquals(1, groups.size());
        List<Integer> group = groups.get(0);
        assertTrue(TrainDeadmanMath.hasFast(consist, group));
        assertTrue(TrainDeadmanMath.nearestDistanceSq(consist, group, 5, 64, 0) <= RADIUS_SQ);
    }

    @Test
    void playerAtConsistTailCountsEvenWhenOnlyHeadIsFast() {
        // 长编组车头超速车尾未超：玩家站车尾（距离 1 格）也按编组最近架算已值守
        List<TrainDeadmanMath.Probe> consist = probes(
                0, 64, 0, 10,
                24, 64, 0, 3.0D,
                48, 64, 0, 3.0D);
        List<Integer> group = TrainDeadmanMath.cluster(consist).get(0);
        assertTrue(TrainDeadmanMath.hasFast(consist, group));
        assertTrue(TrainDeadmanMath.nearestDistanceSq(consist, group, 47, 64, 0) <= RADIUS_SQ);
        // 玩家离开整列编组（最近架 33+ 格）才算无人值守
        assertTrue(TrainDeadmanMath.nearestDistanceSq(consist, group, 81.01D, 64, 0) > RADIUS_SQ);
    }

    @Test
    void parkedNeighborDoesNotCoverMovingConsistWhenApart() {
        // 停放架与快车分属两组（间距超链距）：站停放架旁的玩家对快车组的最近距离仍超半径
        List<TrainDeadmanMath.Probe> all = probes(
                0, 64, 0, 0,
                100, 64, 0, 10);
        List<List<Integer>> groups = TrainDeadmanMath.cluster(all);
        assertEquals(2, groups.size());
        List<Integer> moving = groups.stream()
                .filter(g -> TrainDeadmanMath.hasFast(all, g)).findFirst().orElseThrow();
        assertTrue(TrainDeadmanMath.nearestDistanceSq(all, moving, 5, 64, 0) > RADIUS_SQ);
    }

    @Test
    void parkedBogeyMergedIntoConsistOnlyWidensAttendance() {
        // 停放架与快车被链聚成一组（并排）时并入的是同一编组：距离按全组最近架量
        List<TrainDeadmanMath.Probe> all = probes(
                0, 64, 0, 0,
                24, 64, 0, 10);
        List<List<Integer>> groups = TrainDeadmanMath.cluster(all);
        assertEquals(1, groups.size());
        assertTrue(TrainDeadmanMath.hasFast(all, groups.get(0)));
        assertTrue(TrainDeadmanMath.nearestDistanceSq(all, groups.get(0), 1, 64, 0) <= RADIUS_SQ);
    }

    @Test
    void pulseReleasesOnlyAfterFullPulseWhileLatched() {
        // 锁存期间：通沿 arm → 断沿解除并复位
        var armed = TrainDeadmanMath.stepPulse(false, true, false, true);
        assertTrue(armed.armed());
        assertFalse(armed.release());
        var released = TrainDeadmanMath.stepPulse(true, false, true, true);
        assertFalse(released.armed());
        assertTrue(released.release());
    }

    @Test
    void signalAlreadyHighBeforeLatchDoesNotReleaseOnFall() {
        // 锁存前已常通：arm 为 false 进入锁存，仅断开不构成完整脉冲
        var step = TrainDeadmanMath.stepPulse(true, false, false, true);
        assertFalse(step.armed());
        assertFalse(step.release());
    }

    @Test
    void unlatchedAlwaysClearsArmAndNeverReleases() {
        // 未锁存维度逐 tick 清零 arm：锁存置位瞬间不可能携带残留 arm
        var fromArmed = TrainDeadmanMath.stepPulse(true, true, true, false);
        assertFalse(fromArmed.armed());
        assertFalse(fromArmed.release());
        var falling = TrainDeadmanMath.stepPulse(true, false, true, false);
        assertFalse(falling.armed());
        assertFalse(falling.release());
    }

    @Test
    void steadySignalKeepsStateWithoutRelease() {
        // 持续高位保持 arm 不解除；持续低位无动作
        var steadyHigh = TrainDeadmanMath.stepPulse(true, true, true, true);
        assertTrue(steadyHigh.armed());
        assertFalse(steadyHigh.release());
        var steadyLow = TrainDeadmanMath.stepPulse(false, false, false, true);
        assertFalse(steadyLow.armed());
        assertFalse(steadyLow.release());
    }
}