package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dimblend.spyglass.structure.OutlineBox;
import org.junit.jupiter.api.Test;

class ShapeDepthRangeTest {
    @Test
    void nearPlaneIsHalfTheNearestDistanceWithinItsClamp() {
        assertEquals(100.0F, ShapeDepthRange.nearPlane(200.0));
        assertEquals(ShapeDepthRange.MAX_NEAR, ShapeDepthRange.nearPlane(900.0));
        assertEquals(ShapeDepthRange.MIN_NEAR, ShapeDepthRange.nearPlane(0.04));
        assertEquals(0.5F, ShapeDepthRange.nearPlane(1.0));
    }

    @Test
    void nearPlaneFallsBackToTheFrameNearPlaneInsideABox() {
        assertEquals(ShapeDepthRange.MIN_NEAR, ShapeDepthRange.nearPlane(0.0));
        assertEquals(ShapeDepthRange.MIN_NEAR, ShapeDepthRange.nearPlane(Double.NaN));
        assertEquals(ShapeDepthRange.MIN_NEAR, ShapeDepthRange.nearPlane(-3.0));
    }

    @Test
    void farPlaneReachesPastTheFarthestCornerAndNeverBelowTheRequiredFarPlane() {
        assertEquals(SpyglassProjection.REQUIRED_FAR_PLANE, ShapeDepthRange.farPlane(1100.0));
        assertEquals(5000.0F + ShapeDepthRange.FAR_MARGIN, ShapeDepthRange.farPlane(5000.0));
        assertEquals(SpyglassProjection.REQUIRED_FAR_PLANE, ShapeDepthRange.farPlane(0.0));
        assertEquals(SpyglassProjection.REQUIRED_FAR_PLANE, ShapeDepthRange.farPlane(Double.NaN));
    }

    @Test
    void nearestDistanceUsesTheInclusiveBlockBox() {
        OutlineBox box = new OutlineBox(10, 0, 0, 19, 9, 9); // spans [10, 20) x [0, 10) x [0, 10)

        assertEquals(10.0, ShapeDepthRange.nearestDistance(box, 0.0, 5.0, 5.0), 1.0E-9);
        assertEquals(5.0, ShapeDepthRange.nearestDistance(box, 25.0, 5.0, 5.0), 1.0E-9);
        assertEquals(0.0, ShapeDepthRange.nearestDistance(box, 15.0, 5.0, 5.0), 1.0E-9);
        assertEquals(Math.sqrt(3.0 * 3.0 + 4.0 * 4.0), ShapeDepthRange.nearestDistance(box, 7.0, 14.0, 5.0), 1.0E-9);
    }

    @Test
    void farthestDistanceUsesTheOppositeCorner() {
        OutlineBox box = new OutlineBox(10, 0, 0, 19, 9, 9);

        assertEquals(Math.sqrt(20.0 * 20.0 + 10.0 * 10.0 + 10.0 * 10.0),
                ShapeDepthRange.farthestDistance(box, 0.0, 0.0, 0.0), 1.0E-9);
        assertEquals(Math.sqrt(5.0 * 5.0 + 5.0 * 5.0 + 5.0 * 5.0),
                ShapeDepthRange.farthestDistance(box, 15.0, 5.0, 5.0), 1.0E-9);
    }

    @Test
    void aShapeAtSpyglassRangeGetsAPreciseDepthRange() {
        // A 40-block building whose near face is 300 blocks away: near = 128 (clamped), far = 4096.
        OutlineBox box = new OutlineBox(299, 60, -20, 339, 100, 20);
        double nearest = ShapeDepthRange.nearestDistance(box, 0.0, 70.0, 0.0);
        double farthest = ShapeDepthRange.farthestDistance(box, 0.0, 70.0, 0.0);

        assertEquals(299.0, nearest, 1.0E-9);
        assertEquals(ShapeDepthRange.MAX_NEAR, ShapeDepthRange.nearPlane(nearest));
        assertEquals(SpyglassProjection.REQUIRED_FAR_PLANE, ShapeDepthRange.farPlane(farthest));
    }
}
