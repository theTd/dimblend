package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BedRestRulesTest {
    @Test
    void overworldNeverSkipsNight() {
        assertTrue(BedRestRules.suppressesTimeSkip(true, false, false, false));
        assertTrue(BedRestRules.suppressesTimeSkip(true, false, true, false));
        assertTrue(BedRestRules.suppressesTimeSkip(true, false, false, true));
    }

    @Test
    void rotatingDaytimeNapsRestWithoutSkipping() {
        assertTrue(BedRestRules.suppressesTimeSkip(false, true, true, false));
    }

    @Test
    void rotatingNightAndThunderSleepSkipsToMorning() {
        assertFalse(BedRestRules.suppressesTimeSkip(false, true, false, false));
        assertFalse(BedRestRules.suppressesTimeSkip(false, true, false, true));
        // Vanilla lets beds work through daytime thunderstorms; the skip follows suit.
        assertFalse(BedRestRules.suppressesTimeSkip(false, true, true, true));
    }

    @Test
    void otherDimensionsKeepVanillaSleep() {
        assertFalse(BedRestRules.suppressesTimeSkip(false, false, true, false));
        assertFalse(BedRestRules.suppressesTimeSkip(false, false, false, false));
    }
}
