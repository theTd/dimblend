package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkyTimeJumpTest {
    @Test
    void naturalDayBoundaryIsNotAClockJump() {
        assertFalse(SkyTimeJump.unexpected(23999L, 24000L, 1L));
        assertFalse(SkyTimeJump.unexpected(24000L * 50L - 1L, 24000L * 50L + 9L, 10L));
    }

    @Test
    void multipleTicksBetweenFramesAndPausedOrLockedClocksAreExpected() {
        assertFalse(SkyTimeJump.unexpected(12000L, 12010L, 10L));
        assertFalse(SkyTimeJump.unexpected(18000L, 18000L, 0L));
        assertFalse(SkyTimeJump.unexpected(13000L, 13001L, 0L));
    }

    @Test
    void detectsBothBackwardCorrectionsAndForwardSkips() {
        assertTrue(SkyTimeJump.unexpected(13000L, 12950L, 1L));
        assertTrue(SkyTimeJump.unexpected(12000L, 18000L, 0L));
        assertTrue(SkyTimeJump.unexpected(23999L, 0L, 1L));
    }

    @Test
    void fractionalFastAndReverseDaylightRatesUseTheirActualAdvance() {
        assertFalse(SkyTimeJump.unexpected(12000L, 12002L, 2L));
        assertFalse(SkyTimeJump.unexpected(12000L, 12000L, 0L));
        assertFalse(SkyTimeJump.unexpected(12000L, 12008L, 8L));
        assertFalse(SkyTimeJump.unexpected(12000L, 11996L, -4L));
    }
}
