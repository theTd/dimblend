package dimblend.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TimeLockTargetTest {
    @Test
    void twilightWindowCentersOnTfFixedTime() {
        long min = TimeLockTarget.TWILIGHT_MIN;
        long maxInclusive = TimeLockTarget.TWILIGHT_MAX_EXCLUSIVE - 1;
        assertEquals(13000L, (min + maxInclusive) / 2L, "midpoint must match TF fixed_time 13000");
        assertEquals(100L, maxInclusive - min, "keep the 100-tick wander width");
        assertTrue(min >= 12900L, "must stay on the dark side of the sunset curve");
    }

    @Test
    void fromLaneTable() {
        assertEquals(TimeLockTarget.Mode.TWILIGHT_JITTER, TimeLockTarget.fromLane("twilight").mode());
        assertEquals(18000L, TimeLockTarget.fromLane("underground").time());
        assertEquals(18000L, TimeLockTarget.fromLane("nether").time());
        assertEquals(18000L, TimeLockTarget.fromLane("end").time());
        assertEquals(18000L, TimeLockTarget.fromLane("deeperdarker").time());
        assertEquals(18000L, TimeLockTarget.fromLane("voidscape").time());
        assertEquals(14000L, TimeLockTarget.fromLane("starlight").time());
        assertEquals(4000L, TimeLockTarget.fromLane("aether").time());
        assertEquals(TimeLockTarget.Mode.NONE, TimeLockTarget.fromLane("surface").mode());
        assertEquals(TimeLockTarget.Mode.NONE, TimeLockTarget.fromLane("unknown").mode());
    }

    @Test
    void nonePayloadCarriesWorldDayTime() {
        assertEquals(0L, TimeLockTarget.NONE.time(), "sentinel stays 0 so change detection does not churn");
        assertEquals(12345L, TimeLockTarget.NONE.payloadTime(12345L));
        assertEquals(0L, TimeLockTarget.NONE.payloadTime(0L));
        assertEquals(24000L * 40L + 6000L, TimeLockTarget.fromLane("surface").payloadTime(24000L * 40L + 6000L));
    }

    @Test
    void lockedPayloadIgnoresWorldDayTime() {
        assertEquals(18000L, TimeLockTarget.fromLane("nether").payloadTime(12345L));
        assertEquals(4000L, TimeLockTarget.fromLane("aether").payloadTime(12345L));
        assertEquals(0L, TimeLockTarget.fromLane("twilight").payloadTime(12345L));
    }

    @Test
    void onlyNoneRestoresPacketTime() {
        assertEquals(OptionalLong.of(12345L), TimeLockTarget.Mode.NONE.restoreDayTime(12345L));
        assertEquals(OptionalLong.of(0L), TimeLockTarget.Mode.NONE.restoreDayTime(0L));
        assertTrue(TimeLockTarget.Mode.FIXED.restoreDayTime(18000L).isEmpty());
        assertTrue(TimeLockTarget.Mode.TWILIGHT_JITTER.restoreDayTime(13000L).isEmpty());
    }
}
