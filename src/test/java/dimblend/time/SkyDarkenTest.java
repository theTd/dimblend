package dimblend.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkyDarkenTest {
    @Test
    void endMidnightIsFullyDark() {
        assertEquals(11, SkyDarken.of(18000L, 0.0F, 0.0F));
    }

    @Test
    void aetherMorningIsFullyBright() {
        assertEquals(0, SkyDarken.of(4000L, 0.0F, 0.0F));
    }

    @Test
    void noonIsFullyBright() {
        assertEquals(0, SkyDarken.of(6000L, 0.0F, 0.0F));
    }

    @Test
    void undergroundPredawnIsStillNightDark() {
        assertEquals(11, SkyDarken.of(22000L, 0.0F, 0.0F));
        assertTrue(TimeLockTarget.fromLane("end").time() >= 12000L);
    }
}
