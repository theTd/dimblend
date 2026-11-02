package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class EdgeArrowGeometryTest {
    private static final double GUI = 400.0;
    private static final double INSET = 24.0;
    private static final double RADIUS = 12.0;

    @Test
    void aTargetAheadAndToTheRightLandsOnTheRightEdge() {
        EdgeArrowGeometry.Arrow arrow = EdgeArrowGeometry.compute(
                new Vector3f(1.0F, 0.0F, -1.0F), GUI, GUI, INSET, RADIUS);

        assertNotNull(arrow);
        assertEquals(GUI - INSET, arrow.x(), 1.0E-3);
        assertEquals(GUI * 0.5, arrow.y(), 1.0E-3);
        assertTrue(arrow.dirX() > 0.0F);
        assertEquals(0.0F, arrow.dirY(), 1.0E-6);
    }

    @Test
    void aTargetAboveLandsOnTheTopEdge() {
        EdgeArrowGeometry.Arrow arrow = EdgeArrowGeometry.compute(
                new Vector3f(0.0F, 1.0F, -1.0F), GUI, GUI, INSET, RADIUS);

        assertNotNull(arrow);
        assertEquals(GUI * 0.5, arrow.x(), 1.0E-3);
        assertEquals(INSET, arrow.y(), 1.0E-3);
        assertTrue(arrow.dirY() < 0.0F);
    }

    @Test
    void aTargetBehindPointsLeftOrRightByTheSignOfCamX() {
        EdgeArrowGeometry.Arrow right = EdgeArrowGeometry.compute(
                new Vector3f(3.0F, 0.0F, 2.0F), GUI, GUI, INSET, RADIUS);
        assertNotNull(right);
        assertEquals(1.0F, right.dirX(), 1.0E-6);
        assertEquals(0.0F, right.dirY(), 1.0E-6);

        EdgeArrowGeometry.Arrow left = EdgeArrowGeometry.compute(
                new Vector3f(-3.0F, 0.0F, 2.0F), GUI, GUI, INSET, RADIUS);
        assertNotNull(left);
        assertEquals(-1.0F, left.dirX(), 1.0E-6);

        EdgeArrowGeometry.Arrow straightBehind = EdgeArrowGeometry.compute(
                new Vector3f(0.0F, 0.0F, 2.0F), GUI, GUI, INSET, RADIUS);
        assertNotNull(straightBehind);
        assertEquals(1.0F, straightBehind.dirX(), 1.0E-6, "straight behind is fixed to the right");
    }

    @Test
    void noCandidateMeansNoArrow() {
        assertNull(EdgeArrowGeometry.compute(null, GUI, GUI, INSET, RADIUS));
    }

    @Test
    void aDegenerateRectangleReturnsNullInsteadOfThrowing() {
        assertNull(EdgeArrowGeometry.compute(new Vector3f(1.0F, 0.0F, -1.0F), 20.0, 20.0, INSET, RADIUS));
    }

    @Test
    void anIntersectionInsideTheCrosshairFallsBackToACorner() {
        // 100x100 with inset 24: the right edge is 26 px from the center, inside a 60 px radius.
        EdgeArrowGeometry.Arrow arrow = EdgeArrowGeometry.compute(
                new Vector3f(1.0F, 0.0F, -1.0F), 100.0, 100.0, 24.0, 60.0);

        assertNotNull(arrow);
        double distance = Math.hypot(arrow.x() - 50.0, arrow.y() - 50.0);
        assertTrue(distance >= 60.0, "the fallback corner must clear the crosshair radius: " + distance);
        assertTrue(arrow.x() == 100.0F || arrow.x() == 0.0F, "x must be a rectangle side");
        assertTrue(arrow.y() == 100.0F || arrow.y() == 0.0F, "y must be a rectangle side");
    }
}
