package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DistanceTextTest {
    @Test
    void roundsToTenAndAppendsTheMetreSuffix() {
        assertEquals("~370m", DistanceText.format(370.4));
        assertEquals("~370m", DistanceText.format(365.0));
        assertEquals("~1020m", DistanceText.format(1024.0));
        assertEquals("~0m", DistanceText.format(0.0));
    }
}
