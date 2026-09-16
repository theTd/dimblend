package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RotatingSkyOverlayTest {
    @Test
    void twilightWinsOverStarlightVoidscapeAndEnd() {
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, true, true, true));
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, true, false, false));
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, false, true, true));
        assertEquals(RotatingSkyOverlay.TWILIGHT, RotatingSkyOverlay.of(true, false, false, false));
    }

    @Test
    void starlightWinsOverVoidscapeAndEnd() {
        assertEquals(RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.of(false, true, true, true));
        assertEquals(RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.of(false, true, false, false));
    }

    @Test
    void voidscapeWinsOverEnd() {
        assertEquals(RotatingSkyOverlay.VOIDSCAPE, RotatingSkyOverlay.of(false, false, true, true));
        assertEquals(RotatingSkyOverlay.VOIDSCAPE, RotatingSkyOverlay.of(false, false, true, false));
    }

    @Test
    void endWhenNotTwilightStarlightOrVoidscape() {
        assertEquals(RotatingSkyOverlay.END, RotatingSkyOverlay.of(false, false, false, true));
    }

    @Test
    void noneOutsideTwilightStarlightVoidscapeAndEnd() {
        assertEquals(RotatingSkyOverlay.NONE, RotatingSkyOverlay.of(false, false, false, false));
    }

    @Test
    void holdIfUnloadedKeepsPreviousSample() {
        RotatingSkyOverlay previous = RotatingSkyOverlay.TWILIGHT;
        RotatingSkyOverlay plainsFallback = RotatingSkyOverlay.of(false, false, false, false);
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
    void holdIfUnloadedStillAppliesLaneKeyedSky() {
        assertEquals(
                RotatingSkyOverlay.END,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.END, RotatingSkyOverlay.TWILIGHT));
        assertEquals(
                RotatingSkyOverlay.VOIDSCAPE,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.VOIDSCAPE, RotatingSkyOverlay.TWILIGHT));
        assertEquals(
                RotatingSkyOverlay.VOIDSCAPE,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.VOIDSCAPE, RotatingSkyOverlay.NONE));
    }

    @Test
    void holdIfUnloadedDoesNotKeepLaneKeyedSkyAfterLeavingLane() {
        assertEquals(
                RotatingSkyOverlay.NONE,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.NONE, RotatingSkyOverlay.END));
        assertEquals(
                RotatingSkyOverlay.NONE,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.NONE, RotatingSkyOverlay.VOIDSCAPE));
        assertEquals(
                RotatingSkyOverlay.STARLIGHT,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.END));
        assertEquals(
                RotatingSkyOverlay.STARLIGHT,
                RotatingSkyOverlay.holdIfUnloaded(false, RotatingSkyOverlay.STARLIGHT, RotatingSkyOverlay.VOIDSCAPE));
    }

    @Test
    void laneKeyedOverlaysAreEndAndVoidscape() {
        assertTrue(RotatingSkyOverlay.END.laneKeyed());
        assertTrue(RotatingSkyOverlay.VOIDSCAPE.laneKeyed());
        assertFalse(RotatingSkyOverlay.TWILIGHT.laneKeyed());
        assertFalse(RotatingSkyOverlay.STARLIGHT.laneKeyed());
        assertFalse(RotatingSkyOverlay.NONE.laneKeyed());
    }
}
