package dimblend.radio.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioPlaybackTest {
    private static long seconds(double seconds) {
        return (long) (seconds * 1e9);
    }

    @Test
    void slowServerCannotRewindAChannelRestartOrReplayTheTail() {
        RadioPlayback playback = new RadioPlayback(1, "song", 0, 120, 0, 0);
        // Server has only advanced 80 seconds; the previous resume policy seeks backwards.
        assertEquals(80, RadioStartOffset.offsetSec(80, 80, 0.0));
        assertEquals(110, playback.position(seconds(110)));
        playback.channelEnded(false); // device interruption; retry at 110+, not server's 80
        assertEquals(115, playback.position(seconds(115)));
        assertTrue(playback.finished(seconds(120)));
        assertEquals(120, playback.position(seconds(200)));
    }

    @Test
    void exhaustedChannelEndsPermanentlyEvenBeforeEstimatedDeadline() {
        RadioPlayback playback = new RadioPlayback(1, "song", 0, 120, 0, 0);
        // The old one-second deadline heuristic would retry at this point.
        assertFalse(playback.finished(seconds(117)));
        playback.channelEnded(true);
        assertTrue(playback.finished(seconds(117)));
        assertEquals(120, playback.position(seconds(117))); // goggles and restart guard agree
    }

    @Test
    void readingAheadDoesNotFinishAnActiveChannel() {
        RadioPlayback playback = new RadioPlayback(1, "song", 0, 120, 0, 0);
        // No channelEnded call while channel is active, even with final buffers queued.
        assertFalse(playback.finished(seconds(117)));
        assertEquals(117, playback.position(seconds(117)));
    }

    @Test
    void muteDoesNotResetClockAndLateResumeCannotReplay() {
        RadioPlayback playback = new RadioPlayback(1, "song", 0, 120, 30, 0);
        assertEquals(90, playback.position(seconds(60)));
        // Muting releases the channel, not this object. No server-clock rebase on resume.
        assertTrue(playback.finished(seconds(95)));
        assertEquals(120, playback.position(seconds(95)));
    }

    @Test
    void singlePlayerPauseFreezesProgressAndEof() {
        RadioPlayback playback = new RadioPlayback(1, "song", 0, 120, 30, 0);
        playback.setPaused(true, seconds(10));
        playback.setPaused(true, seconds(200));
        assertEquals(40, playback.position(seconds(250)));
        assertFalse(playback.finished(seconds(250)));
        playback.setPaused(false, seconds(250));
        assertEquals(45, playback.position(seconds(255)));
        assertTrue(playback.finished(seconds(330)));
    }

    @Test
    void nonceHashAndStartTickIdentifyPlayThroughAndAllowIntentionalReplay() {
        RadioPlayback old = new RadioPlayback(1, "song", 20, 120, 0, 0);
        old.channelEnded(true);
        assertTrue(old.matches(1, "song", 20));
        assertFalse(old.matches(2, "song", 20));
        assertFalse(old.matches(1, "other", 20));
        assertFalse(old.matches(1, "song", 40));
        RadioPlayback next = new RadioPlayback(2, "song", 40, 120, 0, seconds(200));
        assertFalse(next.finished(seconds(200)));
        assertEquals(0, next.position(seconds(200)));
    }

    @Test
    void lateJoinGogglesStartAtActualOffsetAndUseDecodedDuration() {
        RadioPlayback playback = new RadioPlayback(1, "song", 0, 123.5, 80.5, seconds(10));
        assertEquals(85.5, playback.position(seconds(15)));
        assertEquals(123.5, playback.duration());
    }
}
