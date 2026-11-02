package dimblend.experience.curse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 消失诅咒点击限流的边界：同一玩家每 tick 两次、下一 tick 重新计数、玩家之间互不影响、
 * 一个玩家只占一条状态（不会随点击次数或 tick 数增长）。
 *
 * <p>为什么要有这条限流：{@link CurseClickPayload} 是不带数据的 C2S 信号，改过的客户端可以
 * 每 tick 狂发，把 0.05% 的单次概率放大成"必定销毁装备"。服务端按玩家按 tick 封顶后，
 * 掷骰预算最多 2 × 20 = 40 次/秒，正常点击完全不受影响。</p>
 */
class CurseClickLimiterTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void capIsTwoRollsPerTick() {
        assertEquals(2, CurseClickLimiter.MAX_ROLLS_PER_TICK);
    }

    @Test
    void firstTwoClicksInTheSameTickPassAndTheThirdIsRefused() {
        CurseClickLimiter limiter = new CurseClickLimiter();
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertFalse(limiter.tryAcquire(ALICE, 100L));
        // 被拒绝之后仍是拒绝：配额不会因为继续点而"恢复"。
        assertFalse(limiter.tryAcquire(ALICE, 100L));
    }

    @Test
    void theNextTickStartsWithAFreshBudget() {
        CurseClickLimiter limiter = new CurseClickLimiter();
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertFalse(limiter.tryAcquire(ALICE, 100L));
        // 101 tick：重新计数，前两次仍旧通过，第三次仍旧拒绝。
        assertTrue(limiter.tryAcquire(ALICE, 101L));
        assertTrue(limiter.tryAcquire(ALICE, 101L));
        assertFalse(limiter.tryAcquire(ALICE, 101L));
    }

    @Test
    void twoPlayersDoNotShareTheBudget() {
        CurseClickLimiter limiter = new CurseClickLimiter();
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertFalse(limiter.tryAcquire(ALICE, 100L));
        // ALICE 用满本 tick 的额度，BOB 在同一 tick 仍各有两次。
        assertTrue(limiter.tryAcquire(BOB, 100L));
        assertTrue(limiter.tryAcquire(BOB, 100L));
        assertFalse(limiter.tryAcquire(BOB, 100L));
        // 反过来再确认 ALICE 下一 tick 的额度没有被 BOB 的点击消耗。
        assertTrue(limiter.tryAcquire(ALICE, 101L));
    }

    @Test
    void oneEntryPerPlayerNoMatterHowManyClicksOrTicks() {
        CurseClickLimiter limiter = new CurseClickLimiter();
        for (long tick = 0L; tick < 1_000L; tick++) {
            assertTrue(limiter.tryAcquire(ALICE, tick));
            assertTrue(limiter.tryAcquire(ALICE, tick));
            assertFalse(limiter.tryAcquire(ALICE, tick));
        }
        // 1000 tick、3000 次点击之后，状态仍只有 ALICE 这一条：窗口是"玩家 → 当前 tick 计数"，
        // 不是点击流水。
        assertEquals(1, limiter.trackedPlayers());
    }

    @Test
    void clearForgetsThePlayerSoTheStateCannotOutliveTheSession() {
        CurseClickLimiter limiter = new CurseClickLimiter();
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        limiter.clear(ALICE);
        assertEquals(0, limiter.trackedPlayers());
        // 清掉之后同一 tick 也能重新开始：登出清状态不会把新会话的第一击算成旧的。
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertTrue(limiter.tryAcquire(ALICE, 100L));
        assertFalse(limiter.tryAcquire(ALICE, 100L));
    }
}
