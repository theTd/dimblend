package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RotatingSkyOverlayTest {
    @Test
    void twilightWinsOverEnd() {
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, true));
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, false));
    }

    @Test
    void endWhenNotTwilight() {
        assertEquals(RotatingSkyOverlay.END, RotatingSkyOverlay.of(false, true));
    }

    @Test
    void noneOutsideTwilightAndEnd() {
        assertEquals(RotatingSkyOverlay.NONE, RotatingSkyOverlay.of(false, false));
    }
}
