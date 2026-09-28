package dimblend.radio.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioClockTest {
    /** Re-anchor at a known nanoTime, then read the clock: later deltas are measured from here. */
    private static long anchor() {
        long nanos = System.nanoTime();
        RadioClock.tick(nanos);
        return nanos;
    }

    @Test
    void lagTickGapsAccumulateInRealTime() {
        long nanos = anchor();
        long base = RadioClock.now();
        // 11 TPS 掉刻间隔 ~91ms：真实流逝多少就补多少，不像 gameTime 只走 50ms
        RadioClock.tick(nanos + 91_000_000L);
        assertEquals(base + 91L, RadioClock.now());
    }

    @Test
    void pauseLikeGapIsCappedNotAccumulatedInFull() {
        long nanos = anchor();
        long base = RadioClock.now();
        // 单人暂停 5 分钟后恢复的第一个 tick：间隔含整段暂停，只按封顶走 1 秒
        RadioClock.tick(nanos + 300_000_000_000L);
        assertEquals(base + RadioClock.MAX_TICK_GAP_MILLIS, RadioClock.now());
    }

    @Test
    void backwardsGapAccumulatesNothing() {
        long nanos = anchor();
        long base = RadioClock.now();
        RadioClock.tick(nanos - 5_000_000_000L);
        assertEquals(base, RadioClock.now());
    }
}
