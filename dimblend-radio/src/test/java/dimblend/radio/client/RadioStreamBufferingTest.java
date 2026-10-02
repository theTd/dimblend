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
