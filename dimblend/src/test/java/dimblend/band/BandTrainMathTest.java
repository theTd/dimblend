package dimblend.band;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * 区域进度条白线的纯逻辑（{@link BandTrainMath}）：force-load 票据 + |z| ≤ 7 + 与玩家水平
 * 距离 ≤ 320 的筛选、|x| 最大的选择、收不到新位置时保留最后一次，以及创造位参与去重 key。
 */
class BandTrainMathTest {
    @Test
    void zAtBoundaryPasses() {
        assertTrue(BandTrainMath.isWithinTrainCorridor(7.0), "z=+7 在界内");
        assertTrue(BandTrainMath.isWithinTrainCorridor(-7.0), "z=-7 在界内");
        assertTrue(BandTrainMath.isWithinTrainCorridor(0.0));
        assertTrue(BandTrainMath.isWithinTrainCorridor(6.999));
    }

    @Test
    void zBeyondBoundaryFails() {
        assertFalse(BandTrainMath.isWithinTrainCorridor(7.001), "z 刚过 7 就不算");
        assertFalse(BandTrainMath.isWithinTrainCorridor(8.0));
        assertFalse(BandTrainMath.isWithinTrainCorridor(-8.0));
        assertFalse(BandTrainMath.isWithinTrainCorridor(Double.NaN), "NaN 不算合格");
    }

    @Test
    void subLevelWithoutTicketFails() {
        BandTrainMath.Candidate withTicket = new BandTrainMath.Candidate(100.0, 3.0, true);
        BandTrainMath.Candidate withoutTicket = new BandTrainMath.Candidate(200.0, 3.0, false);
        assertTrue(BandTrainMath.isEligible(withTicket));
        assertFalse(BandTrainMath.isEligible(withoutTicket), "没有 force-load 票据就不能当白线参照");
        // 无票据那辆即使 |x| 更大也抢不走选择
        assertEquals(OptionalDouble.of(100.0),
                BandTrainMath.selectFrontmostX(List.of(withTicket, withoutTicket), 0.0, 0.0));
    }

    @Test
    void frontmostIsLargestAbsoluteX() {
        BandTrainMath.Candidate near = new BandTrainMath.Candidate(120.0, 0.0, true);
        BandTrainMath.Candidate far = new BandTrainMath.Candidate(-180.0, 5.0, true);
        BandTrainMath.Candidate offTrack = new BandTrainMath.Candidate(310.0, 8.0, true);
        assertEquals(OptionalDouble.of(-180.0),
                BandTrainMath.selectFrontmostX(List.of(near, far, offTrack), 0.0, 0.0),
                "只挑合格候选里 |x| 最大的（离出生点最远）");
    }

    @Test
    void candidateWithinTrackingWindowPasses() {
        // 玩家在原点：319 格在窗口内，320 是含端点的边界
        assertTrue(BandTrainMath.isWithinTrackingWindow(
                new BandTrainMath.Candidate(319.0, 0.0, true), 0.0, 0.0));
        assertTrue(BandTrainMath.isWithinTrackingWindow(
                new BandTrainMath.Candidate(320.0, 0.0, true), 0.0, 0.0), "320 格含端点");
        assertEquals(OptionalDouble.of(319.0),
                BandTrainMath.selectFrontmostX(
                        List.of(new BandTrainMath.Candidate(319.0, 0.0, true)), 0.0, 0.0));
    }

    @Test
    void candidateBeyondTrackingWindowIsIgnored() {
        BandTrainMath.Candidate beyond = new BandTrainMath.Candidate(321.0, 0.0, true);
        assertFalse(BandTrainMath.isWithinTrackingWindow(beyond, 0.0, 0.0), "321 格超窗");
        assertEquals(OptionalDouble.empty(),
                BandTrainMath.selectFrontmostX(List.of(beyond), 0.0, 0.0),
                "超出 320 格的候选不能被选中");
        // 窗口按玩家算：玩家走到它附近就能看见
        assertEquals(OptionalDouble.of(321.0),
                BandTrainMath.selectFrontmostX(List.of(beyond), 100.0, 0.0));
    }

    @Test
    void allCandidatesBeyondWindowKeepsPreviousPosition() {
        List<BandTrainMath.Candidate> outside = List.of(
                new BandTrainMath.Candidate(400.0, 0.0, true),
                new BandTrainMath.Candidate(-900.0, 3.0, true));
        OptionalDouble fresh = BandTrainMath.selectFrontmostX(outside, 0.0, 0.0);
        assertEquals(OptionalDouble.empty(), fresh, "全部超出窗口 → 本次没有结果");
        assertEquals(OptionalDouble.of(555.0), BandTrainMath.keepLast(fresh, 555.0),
                "本次没有结果时沿用上一次收到的位置（需求 #6）");
    }

    @Test
    void emptyResultKeepsPreviousPosition() {
        assertEquals(OptionalDouble.of(4_321.0), BandTrainMath.keepLast(OptionalDouble.empty(), 4_321.0),
                "服务端本次没有结果时沿用上一次收到的位置");
        assertEquals(OptionalDouble.of(12.5), BandTrainMath.keepLast(OptionalDouble.of(12.5), 4_321.0),
                "有本次结果就用本次结果");
        assertEquals(OptionalDouble.empty(), BandTrainMath.keepLast(OptionalDouble.empty(), Double.NaN),
                "从未收到过位置时不画白线");
        assertEquals(OptionalDouble.empty(), BandTrainMath.selectFrontmostX(List.of(), 0.0, 0.0));
    }

    @Test
    void creativeBitIsPartOfLaneDedupeKey() {
        assertEquals(BandTrainMath.lanePushKey(7, false), BandTrainMath.lanePushKey(7, false));
        assertNotEquals(BandTrainMath.lanePushKey(7, false), BandTrainMath.lanePushKey(7, true),
                "同一区域原地切创造模式必须重发");
        assertNotEquals(BandTrainMath.lanePushKey(-7, false), BandTrainMath.lanePushKey(-7, true));
        assertNotEquals(BandTrainMath.lanePushKey(7, true), BandTrainMath.lanePushKey(8, true));
        assertNotEquals(BandTrainMath.lanePushKey(-8, false), BandTrainMath.lanePushKey(-7, false));
    }
}
