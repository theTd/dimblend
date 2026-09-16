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

    @Test
    void holdIfUnloadedKeepsPreviousSample() {
        RotatingSkyOverlay previous = RotatingSkyOverlay.TWILIGHT;
        RotatingSkyOverlay plainsFallback = RotatingSkyOverlay.of(false, false, false);
        assertEquals(
                RotatingSkyOverlay.TWILIGHT,
                RotatingSkyOverlay.holdIfUnloaded(false, plainsFallback, previous));
        assertEquals(
                RotatingSkyOverlay.STARLIGHT,
                RotatingSkyOverlay.holdIfUnloaded(false, plainsFallback, RotatingSkyOverlay.STARLIGHT));
    }

    @Test
    void holdIfUnloadedTakesSampleWhenChunkLoaded() {
        assertEquals(
                RotatingSkyOverlay.NONE,
                RotatingSkyOverlay.holdIfUnloaded(true, RotatingSkyOverlay.NONE, RotatingSkyOverlay.TWILIGHT));
        assertEquals(
                RotatingSkyOverlay.STARLIGHT,
                RotatingSkyOverlay.holdIfUnloaded(true, RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.TWILIGHT));
    }

    @Test
    void holdIfUnloadedStillAppliesLaneKeyedEndSky() {
        assertEquals(
                RotatingSkyOverlay.END,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.END, RotatingSkyOverlay.TWILIGHT));
        assertEquals(
                RotatingSkyOverlay.END,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.END, RotatingSkyOverlay.NONE));
    }

    @Test
    void holdIfUnloadedDoesNotKeepEndSkyAfterLeavingLane() {
        assertEquals(
                RotatingSkyOverlay.NONE,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.NONE, RotatingSkyOverlay.END));
        assertEquals(
                RotatingSkyOverlay.STARLIGHT,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.END));
    }
}
