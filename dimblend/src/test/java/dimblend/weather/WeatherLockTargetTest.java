package dimblend.weather;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeatherLockTargetTest {
    @Test
    void surfaceLaneKeepsSharedWeather() {
        assertFalse(WeatherLockTarget.clearSky("surface"));
    }

    @Test
    void everyOtherLaneIsLockedClear() {
        for (String lane : new String[]{
                "underground", "nether", "end", "twilight", "starlight",
                "aether", "deeperdarker", "voidscape", "mod", "unknown"}) {
            assertTrue(WeatherLockTarget.clearSky(lane), lane);
        }
    }
}
