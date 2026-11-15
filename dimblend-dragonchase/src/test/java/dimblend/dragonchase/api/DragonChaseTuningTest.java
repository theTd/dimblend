package dimblend.dragonchase.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.dragonchase.api.DragonChaseTuning.Option;
import org.junit.jupiter.api.Test;

/**
 * requirements.md §3.1: the eight server-config values, pinned as literals so that any drift in a
 * default or a tunable window fails here instead of silently changing a chase.
 *
 * <p>dimblend-experience's {@code TuningOptionTest} holds the same table for its mirror
 * enumeration; the two must stay identical. Only the enum is touched here, never {@code Config}, so
 * the test needs no loaded NeoForge config.
 */
class DragonChaseTuningTest {
    @Test
    void growthDistanceDefaultsTo1024AndTunesBetween256And4096() {
        // Item 17: the nest gains 0.5 m/s every 1024 blocks by default, tunable 256-4096.
        assertEquals(1024.0D, Option.MIN_SPEED_GROWTH_DISTANCE.defaultValue(), 0.0D);
        assertEquals(256.0D, Option.MIN_SPEED_GROWTH_DISTANCE.minimum(), 0.0D);
        assertEquals(4096.0D, Option.MIN_SPEED_GROWTH_DISTANCE.maximum(), 0.0D);
        assertEquals(16.0D, Option.MIN_SPEED_GROWTH_DISTANCE.step(), 0.0D);
        assertFalse(Option.MIN_SPEED_GROWTH_DISTANCE.checkbox());
        // defineInRange clamps to this window; anything off the 16-block step is refused.
        assertTrue(Option.MIN_SPEED_GROWTH_DISTANCE.isValid(256.0D));
        assertTrue(Option.MIN_SPEED_GROWTH_DISTANCE.isValid(1024.0D));
        assertTrue(Option.MIN_SPEED_GROWTH_DISTANCE.isValid(4096.0D));
        assertFalse(Option.MIN_SPEED_GROWTH_DISTANCE.isValid(255.0D));
        assertFalse(Option.MIN_SPEED_GROWTH_DISTANCE.isValid(4097.0D));
        assertFalse(Option.MIN_SPEED_GROWTH_DISTANCE.isValid(1032.0D));
        assertEquals("1024", Option.MIN_SPEED_GROWTH_DISTANCE.format(1024.0D));
    }

    @Test
    void everyOptionKeepsItsLockedDefaultRangeAndStep() {
        Option[] options = Option.values();
        String[] ids = {"speed_floor_enabled", "generation_speed_step", "health_multiplier",
                "lose_distance", "min_speed_growth_distance", "min_speed_cap",
                "fireball_damage_threshold", "damage_cap_percent"};
        double[] defaults = {1.0D, 1.0D, 1.0D, 512.0D, 1024.0D, 32.0D, 32.0D, 2.0D};
        double[] minima = {0.0D, 0.0D, 0.2D, 256.0D, 256.0D, 16.0D, 4.0D, 0.5D};
        double[] maxima = {1.0D, 8.0D, 5.0D, 1024.0D, 4096.0D, 64.0D, 64.0D, 10.0D};
        double[] steps = {1.0D, 0.5D, 0.2D, 16.0D, 16.0D, 1.0D, 4.0D, 0.5D};
        assertEquals(ids.length, options.length);
        for (int i = 0; i < options.length; i++) {
            Option option = options[i];
            assertEquals(ids[i], option.id(), ids[i]);
            assertEquals(defaults[i], option.defaultValue(), 0.0D, ids[i]);
            assertEquals(minima[i], option.minimum(), 0.0D, ids[i]);
            assertEquals(maxima[i], option.maximum(), 0.0D, ids[i]);
            assertEquals(steps[i], option.step(), 0.0D, ids[i]);
            assertEquals(i == 0, option.checkbox(), ids[i]);
            assertTrue(option.isValid(option.defaultValue()), ids[i]);
            assertFalse(option.isValid(minima[i] - steps[i]), ids[i]);
            assertFalse(option.isValid(maxima[i] + steps[i]), ids[i]);
        }
    }
}
