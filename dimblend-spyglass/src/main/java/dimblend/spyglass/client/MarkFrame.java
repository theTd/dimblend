package dimblend.spyglass.client;

import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureOutline;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * The structures to mark in the frame being rendered, handed from the level pass
 * ({@code RenderLevelStageEvent} AFTER_PARTICLES) to {@link StructureMarkLayer} in the GUI pass of
 * the same frame.
 *
 * <p>It carries <b>every</b> structure in range, not only the ones whose glyph was drawn: the edge
 * arrow still needs the direction of a mark that lost the overlap rule. Each mark also carries its
 * center distance, so the layer does not recompute it.
 *
 * <p>{@code GameRenderer.render} draws the level and then the GUI under the same condition, so the
 * matrices always belong to the frame they are consumed in. Render thread only.
 *
 * @param marks      structures in range this frame
 * @param worldToClip {@code projection × modelView} of this frame, for camera-relative points
 * @param modelView  the frame's model-view, for camera-space directions (the edge arrow)
 * @param cameraX    camera position, subtracted from the world anchor before projecting
 */
record MarkFrame(
        List<Mark> marks,
        Matrix4f worldToClip,
        Matrix4f modelView,
        double cameraX,
        double cameraY,
        double cameraZ) {
    record Mark(StructureOutline outline, boolean predicted, double centerDistance) {
    }

    private static MarkFrame current;

    MarkFrame {
        marks = List.copyOf(marks);
        worldToClip = new Matrix4f(worldToClip);
        modelView = new Matrix4f(modelView);
    }

    /** Builds the hand-off from the live in-range list; the distances use the camera's horizontal position. */
    static MarkFrame of(
            List<StructureOutline> structures,
            Matrix4f worldToClip,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ) {
        List<Mark> marks = new ArrayList<>(structures.size());
        for (StructureOutline structure : structures) {
            marks.add(new Mark(
                    structure,
                    structure.predicted(),
                    SpyglassDistance.horizontal(cameraX, cameraZ, structure)));
        }
        return new MarkFrame(marks, worldToClip, modelView, cameraX, cameraY, cameraZ);
    }

    /** Replaces the hand-off for the current frame; {@code null} means there is nothing to mark. */
    static void publish(@Nullable MarkFrame frame) {
        current = frame;
    }

    /** Returns and clears the hand-off of the current frame. */
    static @Nullable MarkFrame take() {
        MarkFrame frame = current;
        current = null;
        return frame;
    }
}
