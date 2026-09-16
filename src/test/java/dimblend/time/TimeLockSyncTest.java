package dimblend.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TimeLockSyncTest {
    @Test
    void unlockWireUsesWorldClockNotSentinel() {
        TimeLockSync.Wire wire = TimeLockSync.Wire.of(TimeLockTarget.NONE, 12345L);
        assertEquals(TimeLockTarget.Mode.NONE.ordinal(), wire.modeId());
        assertEquals(12345L, wire.time());
        assertEquals(24000L * 40L + 6000L,
                TimeLockSync.Wire.of(TimeLockTarget.fromLane("surface"), 24000L * 40L + 6000L).time());
    }

    @Test
    void changeDetectionIgnoresWorldClock() {
        assertEquals(TimeLockTarget.NONE, TimeLockTarget.fromLane("surface"));
        TimeLockSync.Wire first = TimeLockSync.Wire.of(TimeLockTarget.NONE, 1L);
        TimeLockSync.Wire later = TimeLockSync.Wire.of(TimeLockTarget.NONE, 2L);
        assertEquals(first.modeId(), later.modeId());
        assertNotEquals(first.time(), later.time());
    }

    @Test
    void lockedWireIgnoresWorldClock() {
        TimeLockTarget nether = TimeLockTarget.fromLane("nether");
        TimeLockSync.Wire wire = TimeLockSync.Wire.of(nether, 12345L);
        assertEquals(TimeLockTarget.Mode.FIXED.ordinal(), wire.modeId());
        assertEquals(18000L, wire.time());
        assertEquals(nether, TimeLockTarget.fromLane("underground"));
    }
}
