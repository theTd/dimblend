package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RotatingSkyOverlayTest {
    @Test
    void twilightWinsOverStarlightAndEnd() {
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, true, true));
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, true, false));
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, false, true));
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, false, false));
    }

    @Test
    void starlightWinsOverEnd() {
        assertEquals(RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.of(false, true, true));
        assertEquals(RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.of(false, true, false));
    }

    @Test
    void endWhenNotTwilightOrStarlight() {
        assertEquals(RotatingSkyOverlay.END, RotatingSkyOverlay.of(false, false, true));
    }

    @Test
    void noneOutsideTwilightStarlightAndEnd() {
        assertEquals(RotatingSkyOverlay.NONE, RotatingSkyOverlay.of(false, false, false));
    }
}
