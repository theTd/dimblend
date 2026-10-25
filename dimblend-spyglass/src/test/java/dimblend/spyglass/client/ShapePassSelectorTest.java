package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import org.junit.jupiter.api.Test;

/**
 * Which mesh pass a structure goes to: inside the loaded terrain (overlay after the terrain) or
 * outside it (far pass after the sky). The depth condition keeps overlay meshes inside the frame
 * projection's far plane.
 */
class ShapePassSelectorTest {
    private static final double LOADED_RADIUS = 192.0;
    private static final float DEPTH_FAR = 768.0F;

    @Test
    void aMeshAtTheLoadedEdgeIsAnOverlayOnlyWithinTheMargin() {
        // Box starts at x = 224; the camera is at the origin, so the nearest horizontal distance is
        // 224 and the overlay limit is loadedRadius + 16 = 208 -> far pass.
        OutlineBox pastTheMargin = new OutlineBox(224, 0, 0, 240, 10, 10);
        assertFalse(ShapePassSelector.isOverlay(pastTheMargin, 0, 64, 0, LOADED_RADIUS, DEPTH_FAR));

        OutlineBox insideTheMargin = new OutlineBox(207, 0, 0, 240, 10, 10);
        assertTrue(ShapePassSelector.isOverlay(insideTheMargin, 0, 64, 0, LOADED_RADIUS, DEPTH_FAR));

        // Exactly at the margin is not "inside" any more (strict less-than).
        OutlineBox exactlyAtTheMargin = new OutlineBox(208, 0, 0, 240, 10, 10);
        assertFalse(ShapePassSelector.isOverlay(exactlyAtTheMargin, 0, 64, 0, LOADED_RADIUS, DEPTH_FAR));
    }

    @Test
    void theCameraInsideTheFootprintCountsAsZeroDistance() {
        OutlineBox aroundTheCamera = new OutlineBox(-10, 0, -10, 10, 10, 10);

        assertTrue(ShapePassSelector.isOverlay(
                aroundTheCamera, 0, 64, 0, LOADED_RADIUS, DEPTH_FAR));
        assertEquals(0.0, ShapePassSelector.nearestHorizontalDistance(aroundTheCamera, 0, 0));
    }

    @Test
    void aMeshBeyondTheFrameFarPlaneStaysInTheFarPass() {
        // Nearest distance 100 (overlay), but the farthest corner is past 0.95 * 768 = 729.6.
        OutlineBox huge = new OutlineBox(100, -100, -100, 800, 100, 100);

        assertFalse(ShapePassSelector.isOverlay(huge, 0, 0, 0, LOADED_RADIUS, DEPTH_FAR));
        assertTrue(ShapePassSelector.isOverlay(huge, 0, 0, 0, LOADED_RADIUS, 4096.0F));
    }

    @Test
    void anInvalidDepthRangeNeverUsesTheOverlayPass() {
        OutlineBox close = new OutlineBox(1, 0, 0, 2, 1, 1);

        assertFalse(ShapePassSelector.isOverlay(close, 0, 0, 0, LOADED_RADIUS, 0.0F));
        assertFalse(ShapePassSelector.isOverlay(close, 0, 0, 0, Double.NaN, DEPTH_FAR));
    }

    @Test
    void nearestHorizontalDistanceUsesTheInclusiveFarFaces() {
        OutlineBox box = new OutlineBox(10, 0, -5, 20, 10, 5);

        assertEquals(10.0, ShapePassSelector.nearestHorizontalDistance(box, 0, 0));
        assertEquals(5.0, ShapePassSelector.nearestHorizontalDistance(box, 5, 3));
        // Far faces sit at max + 1: x = 21 and z = 6 are the box faces, so a camera on them is at
        // horizontal distance 0 from the footprint; at z = 20 it is 14 blocks past the z = 6 face.
        assertEquals(0.0, ShapePassSelector.nearestHorizontalDistance(box, 21, 4));
        assertEquals(14.0, ShapePassSelector.nearestHorizontalDistance(box, 21, 20));
    }
}
