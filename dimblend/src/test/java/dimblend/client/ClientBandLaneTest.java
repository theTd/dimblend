package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientBandLaneTest {
    @AfterEach
    void resetLane() {
        ClientBandLane.apply("unknown");
    }

    @Test
    void endLaneIsEnd() {
        ClientBandLane.apply("end");
        assertTrue(ClientBandLane.end());
        assertFalse(ClientBandLane.underground());
    }

    @Test
    void otherLanesAreNotEnd() {
        for (String lane : new String[]{"surface", "underground", "nether", "twilight", "starlight", "unknown"}) {
            ClientBandLane.apply(lane);
            assertFalse(ClientBandLane.end(), lane);
        }
    }

    @Test
    void endSkyLanesUseEndSkybox() {
        for (String lane : new String[]{"end", "deeperdarker"}) {
            ClientBandLane.apply(lane);
            assertTrue(ClientBandLane.endSky(), lane);
            assertFalse(ClientBandLane.netherSky(), lane);
            assertFalse(ClientBandLane.voidscape(), lane);
        }
    }

    @Test
    void netherSkyLanesUseNetherEffects() {
        for (String lane : new String[]{"underground", "nether"}) {
            ClientBandLane.apply(lane);
            assertTrue(ClientBandLane.netherSky(), lane);
            assertFalse(ClientBandLane.endSky(), lane);
            assertFalse(ClientBandLane.voidscape(), lane);
        }
    }

    @Test
    void voidscapeLaneUsesVoidscapeSkyNotEndOrNetherSkybox() {
        ClientBandLane.apply("voidscape");
        assertTrue(ClientBandLane.voidscape());
        assertFalse(ClientBandLane.endSky());
        assertFalse(ClientBandLane.netherSky());
        assertFalse(ClientBandLane.end());
    }

    @Test
    void otherLanesDoNotUseEndOrNetherSkybox() {
        for (String lane : new String[]{"surface", "twilight", "starlight", "aether", "voidscape", "unknown"}) {
            ClientBandLane.apply(lane);
            assertFalse(ClientBandLane.endSky(), lane);
            assertFalse(ClientBandLane.netherSky(), lane);
        }
    }
}
