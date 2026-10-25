package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

class WorldToGuiProjectionTest {
    // 1366x768 at GUI scale 3: the exact GUI size is fractional (guiWidth() would round it up to 456).
    private static final double GUI_WIDTH = 1366.0 / 3.0;
    private static final double GUI_HEIGHT = 768.0 / 3.0;
    private static final float ASPECT = 1366.0F / 768.0F;
    private static final double HALF_SCOPED_FOV = Math.toRadians(VanillaCameraMatrices.SCOPED_FOV / 2.0);
    private static final float EPSILON = 0.01F;

    private final WorldToGuiProjection projection = new WorldToGuiProjection();

    @Test
    void pointStraightAheadLandsOnTheScreenCenter() {
        Vector2f out = new Vector2f();

        assertTrue(project(worldToClip(0.0F, 0.0F), 0.0, 0.0, 500.0, out));

        assertEquals(GUI_WIDTH / 2.0, out.x, EPSILON);
        assertEquals(GUI_HEIGHT / 2.0, out.y, EPSILON);
    }

    @Test
    void followsTheCameraYawAndPitch() {
        Vector2f out = new Vector2f();
        // Facing east and looking 30 degrees up: a point 30 degrees above the horizon to the east is
        // dead center.
        Matrix4f eastUp = worldToClip(-90.0F, -30.0F);

        assertTrue(project(eastUp, 500.0, 500.0 * Math.tan(Math.toRadians(30.0)), 0.0, out));

        assertEquals(GUI_WIDTH / 2.0, out.x, EPSILON);
        assertEquals(GUI_HEIGHT / 2.0, out.y, EPSILON);
    }

    @Test
    void rejectsPointsBehindOrBesideTheCamera() {
        Vector2f out = new Vector2f(-1.0F, -1.0F);
        Matrix4f south = worldToClip(0.0F, 0.0F);

        assertFalse(project(south, 0.0, 0.0, -500.0, out));
        // Exactly sideways: view depth 0 (on the camera plane).
        assertFalse(project(south, 500.0, 0.0, 0.0, out));
        // Just inside the near plane.
        assertFalse(project(south, 0.0, 0.0, WorldToGuiProjection.MIN_VIEW_DEPTH * 0.5, out));
        // A point behind the camera mirrors into the frustum's x/y range; it must still be rejected.
        assertFalse(project(south, 0.0, -1.0, -500.0, out));

        assertEquals(-1.0F, out.x);
        assertEquals(-1.0F, out.y);
    }

    @Test
    void mapsTheViewEdgesToTheGuiEdges() {
        Vector2f out = new Vector2f();
        Matrix4f south = worldToClip(0.0F, 0.0F);
        double halfHeightAt500 = 500.0 * Math.tan(HALF_SCOPED_FOV);
        double halfWidthAt500 = halfHeightAt500 * ASPECT;

        // Facing south the screen's right is west (-X); GUI Y grows downwards.
        assertTrue(project(south, -0.999 * halfWidthAt500, 0.999 * halfHeightAt500, 500.0, out));
        assertEquals(GUI_WIDTH * (1.0 + 0.999) / 2.0, out.x, EPSILON);
        assertEquals(GUI_HEIGHT * (1.0 - 0.999) / 2.0, out.y, EPSILON);

        assertTrue(project(south, 0.5 * halfWidthAt500, -0.5 * halfHeightAt500, 500.0, out));
        assertEquals(GUI_WIDTH * 0.25, out.x, EPSILON);
        assertEquals(GUI_HEIGHT * 0.75, out.y, EPSILON);

        assertFalse(project(south, -1.01 * halfWidthAt500, 0.0, 500.0, out));
        assertFalse(project(south, 0.0, 1.01 * halfHeightAt500, 500.0, out));
    }

    @Test
    void theScopedFovZoomSpreadsOffAxisPointsOutwards() {
        Vector2f scoped = new Vector2f();
        Vector2f unscoped = new Vector2f();
        double above = 500.0 * Math.tan(Math.toRadians(2.0));
        Matrix4f unscopedMatrix = new Matrix4f(VanillaCameraMatrices.projection(
                        VanillaCameraMatrices.DEFAULT_FOV, ASPECT, SpyglassProjection.REQUIRED_FAR_PLANE))
                .mul(VanillaCameraMatrices.modelView(0.0F, 0.0F));

        assertTrue(project(worldToClip(0.0F, 0.0F), 0.0, above, 500.0, scoped));
        assertTrue(project(unscopedMatrix, 0.0, above, 500.0, unscoped));

        double scopedNdc = Math.tan(Math.toRadians(2.0)) / Math.tan(HALF_SCOPED_FOV);
        double unscopedNdc = Math.tan(Math.toRadians(2.0)) / Math.tan(Math.toRadians(35.0));
        assertEquals(GUI_HEIGHT * (1.0 - scopedNdc) / 2.0, scoped.y, EPSILON);
        assertEquals(GUI_HEIGHT * (1.0 - unscopedNdc) / 2.0, unscoped.y, EPSILON);
    }

    @Test
    void theFarPlaneDoesNotMoveTheScreenPosition() {
        Vector2f vanillaFar = new Vector2f();
        Vector2f expandedFar = new Vector2f();
        Matrix4f vanillaProjection = VanillaCameraMatrices.projection(VanillaCameraMatrices.SCOPED_FOV, ASPECT, 768.0F);
        Matrix4f modelView = VanillaCameraMatrices.modelView(30.0F, 10.0F);
        Matrix4f vanilla = new Matrix4f(vanillaProjection).mul(modelView);
        Matrix4f expanded = new Matrix4f(SpyglassProjection.withFarPlane(
                        vanillaProjection, 0.05F, SpyglassProjection.REQUIRED_FAR_PLANE))
                .mul(modelView);
        // Yaw 30 faces south-south-west; pick a point roughly ahead of it, beyond the vanilla far plane.
        double x = -900.0 * Math.sin(Math.toRadians(30.0)) + 3.0;
        double z = 900.0 * Math.cos(Math.toRadians(30.0));
        double y = -900.0 * Math.tan(Math.toRadians(10.0)) + 2.0;

        assertTrue(project(vanilla, x, y, z, vanillaFar));
        assertTrue(project(expanded, x, y, z, expandedFar));

        assertEquals(vanillaFar.x, expandedFar.x, EPSILON);
        assertEquals(vanillaFar.y, expandedFar.y, EPSILON);
    }

    @Test
    void silhouetteTopIsTheNearTopEdgeWhenSeenFromBelow() {
        Matrix4f south = worldToClip(0.0F, 0.0F);
        Vector2f topCenter = new Vector2f();
        Vector2f nearTopEdge = new Vector2f();
        // Box ahead and above eye level: x -10..10, y 5..10, z 450..550.
        assertTrue(project(south, 0.0, 10.0, 500.0, topCenter));
        assertTrue(project(south, 0.0, 10.0, 450.0, nearTopEdge));

        float top = this.projection.silhouetteTop(south, -10.0, 5.0, 450.0, 10.0, 10.0, 550.0, GUI_HEIGHT);

        assertEquals(nearTopEdge.y, top, EPSILON);
        // The near edge is closer, so it projects higher (smaller GUI y) than the top-center.
        assertTrue(top < topCenter.y - 1.0F, "silhouette top " + top + " vs top-center " + topCenter.y);
    }

    @Test
    void silhouetteTopIgnoresTheViewportButRejectsCornersBehindTheCamera() {
        Matrix4f south = worldToClip(0.0F, 0.0F);

        // Corners far outside the screen sideways still count (only their height matters).
        float wide = this.projection.silhouetteTop(south, -5000.0, 5.0, 450.0, 5000.0, 10.0, 550.0, GUI_HEIGHT);
        assertFalse(Float.isNaN(wide));
        // A box reaching behind the camera has no bounded silhouette.
        assertTrue(Float.isNaN(
                this.projection.silhouetteTop(south, -10.0, 5.0, -10.0, 10.0, 10.0, 550.0, GUI_HEIGHT)));
    }

    private boolean project(Matrix4f worldToClip, double x, double y, double z, Vector2f out) {
        return this.projection.project(worldToClip, x, y, z, GUI_WIDTH, GUI_HEIGHT, out);
    }

    private static Matrix4f worldToClip(float yRot, float xRot) {
        return new Matrix4f(VanillaCameraMatrices.projection(
                        VanillaCameraMatrices.SCOPED_FOV, ASPECT, SpyglassProjection.REQUIRED_FAR_PLANE))
                .mul(VanillaCameraMatrices.modelView(yRot, xRot));
    }
}
