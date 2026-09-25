package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The client mask follows the player's band lane; only surface renders weather. */
class ClientWeatherLockTest {
    @AfterEach
    void resetLane() {
        ClientBandLane.apply("unknown");
    }

    @Test
    void surfaceLaneShowsSharedWeather() {
        ClientBandLane.apply("surface");
        assertFalse(ClientWeatherLock.active());
    }

    @Test
    void everyOtherLaneMasksWeather() {
        for (String lane : new String[]{
                "underground", "nether", "end", "twilight", "starlight",
                "aether", "deeperdarker", "voidscape", "mod", "unknown"}) {
            ClientBandLane.apply(lane);
            assertTrue(ClientWeatherLock.active(), lane);
        }
    }
}
