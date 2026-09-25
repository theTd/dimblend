package dimblend.weather;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerGlobalWeatherLockTest {
    @Test
    void surfaceLaneKeepsSharedWeather() {
        assertFalse(ServerGlobalWeatherLock.shouldClear("surface"));
    }

    @Test
    void everyOtherLaneForcesClear() {
        for (String lane : new String[]{
                "underground", "nether", "end", "twilight", "starlight",
                "aether", "deeperdarker", "voidscape", "mod", "unknown"}) {
            assertTrue(ServerGlobalWeatherLock.shouldClear(lane), lane);
        }
    }
}
