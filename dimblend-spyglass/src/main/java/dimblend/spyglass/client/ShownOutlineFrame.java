package dimblend.spyglass.client;

import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * The structures whose real block mesh was actually drawn in the frame being rendered, together with
 * that frame's camera, handed from the level pass ({@code RenderLevelStageEvent} AFTER_PARTICLES) to
 * {@link SpyglassLabelLayer} in the GUI pass of the same frame.
 *
 * <p>{@code GameRenderer.render} draws the level and then the GUI under the same condition
 * ({@code GameRenderer.java:1015,1048}). The level pass replaces the slot on every frame and the GUI
 * pass empties it, so labels are always projected with the matrices of the frame they are drawn in
 * and a frame without a level pass never reuses old ones. Render thread only.
 *
 * @param structures structures whose mesh was drawn this frame, in the far or the overlay pass
 * @param worldToClip {@code projection × modelView} of this frame, for camera-relative points
 */
record ShownOutlineFrame(
        List<StructureOutline> structures,
        Matrix4f worldToClip,
        double cameraX,
        double cameraY,
        double cameraZ) {
    private static ShownOutlineFrame current;

    ShownOutlineFrame {
        structures = List.copyOf(structures);
        worldToClip = new Matrix4f(worldToClip);
    }

    /** Replaces the hand-off for the current frame; {@code null} means nothing was drawn. */
    static void publish(@Nullable ShownOutlineFrame frame) {
        current = frame;
    }

    /** Returns and clears the hand-off of the current frame. */
    static @Nullable ShownOutlineFrame take() {
        ShownOutlineFrame frame = current;
        current = null;
        return frame;
    }
}
