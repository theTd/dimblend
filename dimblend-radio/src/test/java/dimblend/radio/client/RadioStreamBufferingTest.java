package dimblend.radio.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioStreamBufferingTest {
    @Test void queueHasSchedulerHeadroomAtEverySupportedTrackRate() {
        for (int rate : new int[] {22050, 32000, 44100, 48000, 96000, 192000}) {
            int buffers = RadioStreamBuffering.bufferCount(rate);
            assertTrue(buffers >= 2);
            double queued = buffers * (double) dimblend.radio.acoustics.SteamRenderer.FRAME / rate;
            assertTrue(queued >= 0.025);
            assertTrue(queued < 0.050, "Head turns must not be delayed by a deep spatialized queue");
        }
    }

    @Test void repeatedStarvationAddsAndRetainsEnoughHeadroomForTimingSpikes() {
        int rate = 48000;
        var state = new RadioStreamBuffering.State(rate);
        int initial = state.target();
        assertTrue(initial * 512.0 / rate < 0.05);
        assertTrue(state.underrun() > initial);
        state.underrun();
        assertTrue(state.target() * 512.0 / rate > 0.064, "Must absorb the observed 64 ms sound-executor spike");
        int grown = state.target();
        assertEquals(grown, state.target(), "Headroom must not collapse again before the next refill");
        for (int i=0; i<100; i++) state.underrun();
        assertTrue(state.target() * 512.0 / rate < 0.105, "Recovery must not grow into seconds of stale spatial audio");
    }

    /** Plays {@code seconds} of steady audio: one buffer finishes per refill and is replaced. */
    private static void play(RadioStreamBuffering.State state, int rate, double seconds, int lowest) {
        int refills = (int) Math.ceil(seconds * rate / 512);
        for (int i = 0; i < refills; i++) {
            int queued = i % 50 == 0 ? Math.min(lowest, state.target() - 1) : state.target() - 1;
            int added = state.refill(1, queued);
            assertEquals(Math.max(0, state.target() - queued), added);
        }
    }

    @Test void headroomIsGivenBackOnceDeepQueuesStayFull() {
        int rate = 48000;
        var state = new RadioStreamBuffering.State(rate);
        int base = state.target();
        state.underrun();
        int grown = state.target();
        assertEquals(base + 2, grown);
        play(state, rate, 7, Integer.MAX_VALUE);
        assertEquals(grown - 1, state.target(), "one buffer back after a stable window (doubled by the underrun)");
        play(state, rate, 30, Integer.MAX_VALUE);
        assertEquals(base, state.target(), "back to the short start, never below it");
    }

    @Test void aQueueThatRanLowKeepsItsHeadroom() {
        int rate = 48000;
        var state = new RadioStreamBuffering.State(rate);
        state.underrun();
        int grown = state.target();
        play(state, rate, 30, 1);
        assertEquals(grown, state.target(), "a hitch that left one buffer would starve after a shrink");
    }

    @Test void aPausedSourceNeverCountsAsStable() {
        int rate = 48000;
        var state = new RadioStreamBuffering.State(rate);
        state.underrun();
        int grown = state.target();
        for (int i = 0; i < 100_000; i++) assertEquals(0, state.refill(0, grown));
        assertEquals(grown, state.target());
    }

    @Test void repeatedUnderrunsWaitLongerBeforeShrinking() {
        int rate = 48000;
        var once = new RadioStreamBuffering.State(rate);
        once.underrun();
        var thrice = new RadioStreamBuffering.State(rate);
        thrice.underrun();
        thrice.underrun();
        thrice.underrun();
        int onceGrown = once.target(), thriceGrown = thrice.target();
        play(once, rate, 7, Integer.MAX_VALUE);
        play(thrice, rate, 7, Integer.MAX_VALUE);
        assertTrue(once.target() < onceGrown);
        assertEquals(thriceGrown, thrice.target(), "periodic hitches must not cause a starve/shrink cycle");
    }

    @Test void laterPlaybacksStartWithTheLearnedHeadroomWhichDecays() {
        int rate = 48000;
        RadioStreamBuffering.forgetLearned();
        try {
            var first = RadioStreamBuffering.forPlayback(rate);
            int base = first.target();
            assertEquals(new RadioStreamBuffering.State(rate).target(), base, "Nothing learned yet: start short");
            first.underrun();
            first.underrun();
            assertEquals(base + 4, RadioStreamBuffering.forPlayback(rate).target(), "A new track must not starve again to relearn");
            assertEquals(base + 3, RadioStreamBuffering.forPlayback(rate).target(), "Each new playback gives one buffer back");
            new RadioStreamBuffering.State(rate).underrun();
            assertEquals(base + 2, RadioStreamBuffering.forPlayback(rate).target(), "Standalone state does not teach");
            for (int i = 0; i < 10; i++) RadioStreamBuffering.forPlayback(rate);
            assertEquals(base, RadioStreamBuffering.forPlayback(rate).target());
        } finally {
            RadioStreamBuffering.forgetLearned();
        }
    }
}
