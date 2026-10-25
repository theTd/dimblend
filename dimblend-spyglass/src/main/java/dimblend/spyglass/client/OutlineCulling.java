package dimblend.spyglass.client;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureOutline;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * View-frustum test for outline boxes, allocation-free per box.
 *
 * <p>The frustum must be built from the far-plane-adjusted projection used to draw the outlines, not
 * from the vanilla one: vanilla's far plane sits at {@code GameRenderer.getDepthFar()} =
 * 4 × render distance ({@code GameRenderer.java:982-984}), which would cull every outline past the
 * loaded terrain. Side planes are identical either way; only the far plane differs.
 *
 * <p>Mirrors {@code net.minecraft.client.renderer.culling.Frustum}: the clip matrix is
 * {@code projection × modelView} ({@code projection.mul(modelView)}, {@code Frustum.java:66-70};
 * JOML's {@code a.mul(b)} is {@code a × b}, so the model-view is applied to the point first) and
 * boxes are tested relative to the camera position ({@code Frustum.java:76-84}). The model-view of
 * {@code RenderLevelStageEvent} is the camera rotation, so the reverse order would test boxes as if
 * the camera always faced north.
 */
final class OutlineCulling {
    private final FrustumIntersection intersection = new FrustumIntersection();
    private final Matrix4f clipSpace = new Matrix4f();
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    void update(Matrix4f modelView, Matrix4f projection, Vec3 camera) {
        projection.mul(modelView, this.clipSpace);
        this.intersection.set(this.clipSpace);
        this.cameraX = camera.x;
        this.cameraY = camera.y;
        this.cameraZ = camera.z;
    }

    boolean isVisible(StructureOutline outline) {
        return isVisible(outline.bounds());
    }

    boolean isVisible(OutlineBox box) {
        return this.intersection.testAab(
                (float) (box.minX() - this.cameraX),
                (float) (box.minY() - this.cameraY),
                (float) (box.minZ() - this.cameraZ),
                (float) (box.maxX() + 1.0 - this.cameraX),
                (float) (box.maxY() + 1.0 - this.cameraY),
                (float) (box.maxZ() + 1.0 - this.cameraZ));
    }
}
