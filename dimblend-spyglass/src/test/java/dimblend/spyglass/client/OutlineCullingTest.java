package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class OutlineCullingTest {
    private static final Matrix4f MODEL_VIEW = new Matrix4f();

    @Test
    void seesOutlinesInsideTheExpandedFarPlane() {
        OutlineCulling culling = new OutlineCulling();
        culling.update(MODEL_VIEW, projection(SpyglassProjection.REQUIRED_FAR_PLANE), Vec3.ZERO);

        assertTrue(culling.isVisible(new OutlineBox(-10, -10, -500, 10, 10, -490)));
        assertTrue(culling.isVisible(new OutlineBox(-10, -10, -1000, 10, 10, -990)));
    }

    @Test
    void theVanillaFarPlaneWouldCullDistantOutlines() {
        // GameRenderer.getDepthFar() is 4 x render distance in blocks (GameRenderer.java:982-984),
        // e.g. 768 blocks at 12 chunks; nothing beyond that may be culled by our pass.
        Matrix4f vanillaFarPlane = projection(768.0F);
        OutlineCulling withVanillaFar = new OutlineCulling();
        withVanillaFar.update(MODEL_VIEW, vanillaFarPlane, Vec3.ZERO);
        OutlineCulling withOutlinesFar = new OutlineCulling();
        withOutlinesFar.update(MODEL_VIEW, projection(SpyglassProjection.REQUIRED_FAR_PLANE), Vec3.ZERO);

        OutlineBox outline = new OutlineBox(-10, -10, -900, 10, 10, -890);

        assertFalse(withVanillaFar.isVisible(outline));
        assertTrue(withOutlinesFar.isVisible(outline));
    }

    @Test
    void cullsBoxesBehindTheCameraAndOutsideTheSidePlanes() {
        OutlineCulling culling = new OutlineCulling();
        culling.update(MODEL_VIEW, projection(SpyglassProjection.REQUIRED_FAR_PLANE), Vec3.ZERO);

        assertFalse(culling.isVisible(new OutlineBox(-10, -10, 10, 10, 10, 20)));
        assertFalse(culling.isVisible(new OutlineBox(2000, -10, -500, 2010, 10, -490)));
        assertFalse(culling.isVisible(new OutlineBox(-10, -10, -5000, 10, 10, -4990)));
    }

    @Test
    void respectsTheCameraPositionOffset() {
        OutlineCulling culling = new OutlineCulling();
        culling.update(MODEL_VIEW, projection(SpyglassProjection.REQUIRED_FAR_PLANE), new Vec3(0, 0, 1000));

        assertTrue(culling.isVisible(new OutlineBox(-10, -10, -900, 10, 10, -890)));
        assertFalse(culling.isVisible(new OutlineBox(-10, -10, 1500, 10, 10, 1510)));
    }

    @Test
    void testsBoxesInTheDirectionTheCameraFaces() {
        // RenderLevelStageEvent's model-view is the camera rotation. The clip matrix must be
        // projection x modelView (Frustum.java:66-70); the reverse order keeps clip w = -worldZ and
        // treats every camera as facing north.
        OutlineCulling facingSouth = new OutlineCulling();
        facingSouth.update(VanillaCameraMatrices.modelView(0.0F, 0.0F),
                projection(SpyglassProjection.REQUIRED_FAR_PLANE), Vec3.ZERO);
        assertTrue(facingSouth.isVisible(new OutlineBox(-10, -10, 490, 10, 10, 500)));
        assertFalse(facingSouth.isVisible(new OutlineBox(-10, -10, -500, 10, 10, -490)));

        OutlineCulling facingEast = new OutlineCulling();
        facingEast.update(VanillaCameraMatrices.modelView(-90.0F, 0.0F),
                projection(SpyglassProjection.REQUIRED_FAR_PLANE), Vec3.ZERO);
        assertTrue(facingEast.isVisible(new OutlineBox(490, -10, -10, 500, 10, 10)));
        assertFalse(facingEast.isVisible(new OutlineBox(-10, -10, -500, 10, 10, -490)));
        assertFalse(facingEast.isVisible(new OutlineBox(-500, -10, -10, -490, 10, 10)));
    }

    private static Matrix4f projection(float far) {
        return new Matrix4f().perspective(
                (float) Math.toRadians(70.0), 1.0F, 0.05F, far);
    }
}
