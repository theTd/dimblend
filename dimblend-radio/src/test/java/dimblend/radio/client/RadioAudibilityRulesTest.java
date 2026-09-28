package dimblend.radio.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class RadioAudibilityRulesTest {
    @ParameterizedTest
    @CsvSource({"0,10,false", "15,10,false", "1,0,false", "14,0,false",
            "1,1,true", "14,15,true", "-1,10,false", "16,10,false", "1,16,false"})
    void invalidTopOrMutedSideNeverSuppressesEvenWithAStaleLiveChannel(int top, int side, boolean expected) {
        assertEquals(expected, RadioAudibilityRules.shouldSuppress(top, side, 1, 2, 64));
    }

    @Test
    void propagationBoundaryHasZeroGainAndDoesNotSuppress() {
        assertTrue(RadioAudibilityRules.shouldSuppress(1, 10, 1, 63.999, 64));
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, 1, 64, 64));
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, 1, 65, 64));
    }

    @Test
    void noPlayingChannelDoesNotSuppressDespiteValidServerState() {
        // playingGain is zero for missing/decoding/finished/failed/paused/stopped channels.
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, 0, 0, 64));
    }

    @Test
    void eitherVolumeSliderAtZeroReleasesMusic() {
        double channel = 0.5;
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, channel * 0.0 * 1.0, 2, 64));
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, channel * 1.0 * 0.0, 2, 64));
        assertTrue(RadioAudibilityRules.shouldSuppress(1, 10, channel * 0.2 * 0.3, 2, 64));
    }

    @Test
    void invalidGainOrDistanceCannotSuppress() {
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, Double.NaN, 2, 64));
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, -0.1, 2, 64));
        assertFalse(RadioAudibilityRules.shouldSuppress(1, 10, 1, Double.NaN, 64));
    }

    @Test
    void silentRadioDoesNotPreventAnotherAudibleRadioFromSuppressing() {
        boolean missing = RadioAudibilityRules.shouldSuppress(1, 10, 0, 2, 64);
        boolean distant = RadioAudibilityRules.shouldSuppress(2, 10, 1, 80, 64);
        boolean nearby = RadioAudibilityRules.shouldSuppress(3, 15, 1, 10, 64);
        assertFalse(missing || distant);
        assertTrue(missing || distant || nearby);
    }
}
