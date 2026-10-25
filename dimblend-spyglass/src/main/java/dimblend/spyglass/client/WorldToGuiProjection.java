package dimblend.spyglass.client;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * Projects a camera-relative world point to GUI coordinates with the matrices of the frame being
 * rendered.
 *
 * <p>{@code worldToClip} is {@code projection × modelView} as handed to
 * {@code RenderLevelStageEvent}: the projection already carries the scoped FOV, view bobbing and
 * nausea warp ({@code GameRenderer.java:1233-1255}), the model-view is the camera rotation
 * ({@code GameRenderer.java:1256-1257}). The far plane only changes clip {@code z}, so a
 * far-plane-adjusted projection yields the same screen position.
 *
 * <p>The GUI size must be the exact framebuffer size divided by the GUI scale — that is what the GUI
 * orthographic projection is built from ({@code GameRenderer.java:1030-1041}). {@code guiWidth()} /
 * {@code guiHeight()} round that value up ({@code Window.java:396-402}) and would shift points by up
 * to one GUI pixel.
 *
 * <p>Not thread-safe and reuses its buffer: keep one instance per render thread.
 */
final class WorldToGuiProjection {
    /**
     * Smallest view depth (clip {@code w}) that still counts as in front of the camera; matches the
     * near plane of the outline pass. Points at or behind it have no meaningful screen position:
     * dividing by {@code w <= 0} mirrors them through the screen center.
     */
    static final float MIN_VIEW_DEPTH = 0.05F;

    private final Vector4f clip = new Vector4f();

    /**
     * @param worldToClip {@code projection × modelView} of the current frame
     * @param relX point minus camera position, X (likewise Y, Z)
     * @param guiWidth exact GUI-space viewport width: framebuffer width / GUI scale
     * @param guiHeight exact GUI-space viewport height: framebuffer height / GUI scale
     * @param out receives the GUI position (origin top-left, Y down); untouched on rejection
     * @return {@code false} if the point is behind the camera or outside the viewport
     */
    boolean project(
            Matrix4f worldToClip,
            double relX,
            double relY,
            double relZ,
            double guiWidth,
            double guiHeight,
            Vector2f out) {
        worldToClip.transform(this.clip.set((float) relX, (float) relY, (float) relZ, 1.0F));
        float w = this.clip.w();
        if (!(w > MIN_VIEW_DEPTH)) {
            return false;
        }
        float ndcX = this.clip.x() / w;
        float ndcY = this.clip.y() / w;
        if (!(Math.abs(ndcX) <= 1.0F && Math.abs(ndcY) <= 1.0F)) {
            return false;
        }
        out.set(
                (float) ((ndcX + 1.0) * 0.5 * guiWidth),
                (float) ((1.0 - ndcY) * 0.5 * guiHeight));
        return true;
    }

    /**
     * Topmost GUI Y of a camera-relative box's silhouette, i.e. the smallest projected Y of its eight
     * corners. Corners are not checked against the viewport (only their height matters, and a large
     * box may extend past the screen edges).
     *
     * <p>The projected top-center is not the silhouette's top: seen from below, the near top edge is
     * closer to the camera and projects higher on screen than the top face's center.
     *
     * @return the topmost GUI Y, or {@link Float#NaN} if any corner is at or behind
     *         {@link #MIN_VIEW_DEPTH} (the silhouette is then unbounded on screen)
     */
    float silhouetteTop(
            Matrix4f worldToClip,
            double minRelX,
            double minRelY,
            double minRelZ,
            double maxRelX,
            double maxRelY,
            double maxRelZ,
            double guiHeight) {
        float top = Float.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? minRelX : maxRelX;
            double y = (corner & 2) == 0 ? minRelY : maxRelY;
            double z = (corner & 4) == 0 ? minRelZ : maxRelZ;
            worldToClip.transform(this.clip.set((float) x, (float) y, (float) z, 1.0F));
            float w = this.clip.w();
            if (!(w > MIN_VIEW_DEPTH)) {
                return Float.NaN;
            }
            top = Math.min(top, (float) ((1.0 - this.clip.y() / w) * 0.5 * guiHeight));
        }
        return top;
    }
}
