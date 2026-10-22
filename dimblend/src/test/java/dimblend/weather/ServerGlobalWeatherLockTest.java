package dimblend.weather;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.RandomSource;
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

    @Test
    void freshWeatherDelayMatchesVanillaRange() {
        // Vanilla RAIN_DELAY/THUNDER_DELAY are UniformInt.of(12000, 180000), inclusive.
        var random = RandomSource.create(42L);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 100_000; i++) {
            int delay = ServerGlobalWeatherLock.freshWeatherDelay(random);
            assertTrue(delay >= 12000 && delay <= 180000, "delay out of range: " + delay);
            min = Math.min(min, delay);
            max = Math.max(max, delay);
        }
        // Uniform sampling must cover the edges of the range over this many draws.
        assertTrue(min < 12000 + 1680, "never sampled near the lower bound: " + min);
        assertTrue(max > 180000 - 1680, "never sampled near the upper bound: " + max);
    }
}
