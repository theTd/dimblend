package dimblend.weather;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeatherLockTargetTest {
    @Test
    void lockedLanesForceClearSky() {
        assertTrue(WeatherLockTarget.clearSky("underground"));
        assertTrue(WeatherLockTarget.clearSky("aether"));
        assertTrue(WeatherLockTarget.clearSky("deeperdarker"));
        assertTrue(WeatherLockTarget.clearSky("voidscape"));
    }

    @Test
    void unlockedLanesKeepVanillaWeather() {
        assertFalse(WeatherLockTarget.clearSky("surface"));
        assertFalse(WeatherLockTarget.clearSky("nether"));
        assertFalse(WeatherLockTarget.clearSky("end"));
        assertFalse(WeatherLockTarget.clearSky("twilight"));
        assertFalse(WeatherLockTarget.clearSky("starlight"));
        assertFalse(WeatherLockTarget.clearSky("unknown"));
        assertFalse(WeatherLockTarget.clearSky("mod"));
    }
}
