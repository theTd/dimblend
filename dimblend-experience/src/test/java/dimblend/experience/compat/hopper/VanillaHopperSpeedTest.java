package dimblend.experience.compat.hopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** LOCKED DECISION #13：漏斗节律 8 → 2 tick（±×4、10 个/秒），两条不变式钉死。 */
class VanillaHopperSpeedTest {

    @Test
    void cooldownIsTwoTicks() {
        assertEquals(2, VanillaHopperSpeed.COOLDOWN_TICKS);
    }

    @Test
    void multiplierIsExactlyFour() {
        assertEquals(4, VanillaHopperSpeed.multiplier());
    }

    @Test
    void tenMovesPerSecond() {
        assertEquals(10, VanillaHopperSpeed.movesPerSecond());
    }

    @Test
    void rhythmMustDivideTheSecond() {
        assertTrue(VanillaHopperSpeed.dividesSecond(VanillaHopperSpeed.COOLDOWN_TICKS));
        assertTrue(VanillaHopperSpeed.dividesSecond(4));
        assertFalse(VanillaHopperSpeed.dividesSecond(3));
        assertFalse(VanillaHopperSpeed.dividesSecond(0));
    }
}
