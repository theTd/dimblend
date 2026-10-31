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

    @Test
    void restTimingMatchesTheEightSecondSleepHeal() {
        assertEquals(160, BedRestRules.REST_REGENERATION_DURATION_TICKS);
        assertEquals(9, BedRestRules.REST_REGENERATION_AMPLIFIER);
        assertEquals(160, BedRestRules.REST_AUTO_WAKE_TICKS);
        assertEquals(20, BedRestRules.HEART_PARTICLE_INTERVAL_TICKS);
        assertEquals(3, BedRestRules.HEART_PARTICLE_COUNT);
    }

    @Test
    void regenerationIsGrantedOnTheFirstRestingTickOnly() {
        assertFalse(BedRestRules.grantsRegenerationOnTick(0));
        assertTrue(BedRestRules.grantsRegenerationOnTick(1));
        assertFalse(BedRestRules.grantsRegenerationOnTick(2));
        assertFalse(BedRestRules.grantsRegenerationOnTick(159));
        assertFalse(BedRestRules.grantsRegenerationOnTick(160));
    }

    @Test
    void heartsStartOnTheFirstTickAndRepeatEverySecond() {
        assertFalse(BedRestRules.emitsHeartsOnTick(0));
        assertTrue(BedRestRules.emitsHeartsOnTick(1));
        assertFalse(BedRestRules.emitsHeartsOnTick(20));
        assertTrue(BedRestRules.emitsHeartsOnTick(21));
        assertTrue(BedRestRules.emitsHeartsOnTick(141));
        assertFalse(BedRestRules.emitsHeartsOnTick(160));
    }

    @Test
    void restEndsAutomaticallyAfterEightSeconds() {
        assertFalse(BedRestRules.autoWakesOnTick(0));
        assertFalse(BedRestRules.autoWakesOnTick(159));
        assertTrue(BedRestRules.autoWakesOnTick(160));
        assertTrue(BedRestRules.autoWakesOnTick(161));
    }
}
