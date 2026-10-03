package dimblend.radio.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticIdleGateTest {
    private static final double[] QUIET = {0.2, 0.3, 0.1};

    @Test
    void awayUsesHalfTheProcessorsUpToEight() {
        var away = AcousticIdleGate.decide(false, true, new double[0], false, 16);
        assertEquals(8, away.threads());
        assertEquals(AcousticIdleGate.AWAY_BUDGET_SECONDS, away.seconds());
        assertEquals(2, AcousticIdleGate.decide(false, true, new double[0], false, 4).threads());
        assertEquals(1, AcousticIdleGate.decide(false, true, new double[0], false, 1).threads());
        assertEquals(8, AcousticIdleGate.decide(false, true, new double[0], false, 64).threads());
    }

    @Test
    void aQuietCpuWithSmoothFramesAllowsOneOrTwoThreadsWhilePlaying() {
        var quiet = AcousticIdleGate.decide(false, false, QUIET, true, 8);
        assertEquals(1, quiet.threads());
        assertEquals(AcousticIdleGate.IDLE_BUDGET_SECONDS, quiet.seconds());
        assertEquals(2, AcousticIdleGate.decide(false, false, QUIET, true, 12).threads());
    }

    @Test
    void loadStutterOrTooFewSamplesKeepItClosed() {
        assertFalse(AcousticIdleGate.decide(false, false, new double[] {0.2, 0.6, 0.1}, true, 8).open(), "one busy second");
        assertFalse(AcousticIdleGate.decide(false, false, new double[] {0.2, 0.2}, true, 8).open(), "not three seconds yet");
        assertFalse(AcousticIdleGate.decide(false, false, new double[] {0.2, Double.NaN, 0.1}, true, 8).open(), "unknown load");
        assertFalse(AcousticIdleGate.decide(false, false, QUIET, false, 8).open(), "frames behind");
        assertTrue(AcousticIdleGate.decide(false, false, new double[] {0.9, 0.1, 0.1, 0.1}, true, 8).open(),
                "only the latest three seconds count");
    }

    @Test
    void neverWhileLoading() {
        assertFalse(AcousticIdleGate.decide(true, true, QUIET, true, 16).open());
        assertFalse(AcousticIdleGate.decide(true, false, QUIET, true, 16).open());
    }

    @Test
    void bakeEstimatesKeepAFloorLevelWithinTheIdleBudget() {
        assertTrue(AcousticBakeScheduler.estimate(800, 1) < AcousticIdleGate.IDLE_BUDGET_SECONDS);
        assertTrue(AcousticBakeScheduler.estimate(AcousticBakeScheduler.MAX_PROBES, 1) < AcousticIdleGate.IDLE_BUDGET_SECONDS);
        assertTrue(AcousticBakeScheduler.estimate(1000, 4) < AcousticBakeScheduler.estimate(1000, 2));
        assertEquals(AcousticBakeScheduler.estimate(1000, 8), AcousticBakeScheduler.estimate(1000, 32), 1e-9,
                "more threads stop helping");
    }
}
