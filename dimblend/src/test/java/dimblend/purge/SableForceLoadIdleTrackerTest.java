package dimblend.purge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SableForceLoadIdleTrackerTest {

    @Test
    void releasesOnlyAfterContinuousIdleThresholdAndResetsWhenInterrupted() {
        SableForceLoadIdleTracker tracker = new SableForceLoadIdleTracker();
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Tick 100: both start counting; idleTicks = 2400 -> not ready yet
        assertTrue(tracker.update(List.of(a, b), 100L, 2400L).isEmpty());
        assertEquals(2, tracker.tracked());

        // Tick 1000: b briefly leaves eligibility -> b's timer is dropped/reset; a keeps elapsed time
        assertTrue(tracker.update(List.of(a), 1000L, 2400L).isEmpty());
        assertEquals(1, tracker.tracked());

        // Tick 2500: a reaches 2400 ticks (2500 - 100 == 2400) -> released; b restarts at 2500
        List<UUID> released = tracker.update(List.of(a, b), 2500L, 2400L);
        assertEquals(List.of(a), released);
        assertEquals(1, tracker.tracked());

        // Tick 4899: b is 1 tick short -> still waiting
        assertTrue(tracker.update(List.of(b), 4899L, 2400L).isEmpty());

        // Tick 4900: b reaches 2400 ticks -> released
        assertEquals(List.of(b), tracker.update(List.of(b), 4900L, 2400L));
        assertEquals(0, tracker.tracked());
    }

    @Test
    void forceScanZeroIdleReleasesImmediately() {
        SableForceLoadIdleTracker tracker = new SableForceLoadIdleTracker();
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000010");
        assertEquals(List.of(a), tracker.update(List.of(a), 50L, 0L));
        assertEquals(0, tracker.tracked());
    }
}
