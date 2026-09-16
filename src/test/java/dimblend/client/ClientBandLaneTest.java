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
}
