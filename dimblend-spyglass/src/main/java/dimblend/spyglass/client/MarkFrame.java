package dimblend.spyglass.client;

import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureOutline;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * The structures to mark in the frame being rendered, handed from the level pass
 * ({@code RenderLevelStageEvent} AFTER_PARTICLES) to {@link StructureMarkLayer} in the GUI pass of
 * the same frame.
 *
 * <p>It carries <b>every</b> structure in range, not only the ones whose glyph was drawn: the edge
 * arrow still needs the direction of a mark that lost the overlap rule. Each mark also carries its
 * saved {@link StructureSelection.Entry} (key, anchor, prediction flag) and its center distance, so
 * the layer does not recompute them.
 *
 * <p>When the selected structure is no longer in the snapshot, {@link #withSelection} adds it from
 * the saved anchor: the selection is a stable world position, not a snapshot entry.
 *
 * <p>{@code GameRenderer.render} draws the level and then the GUI under the same condition, so the
 * matrices always belong to the frame they are consumed in. Render thread only.
 *
 * @param marks      structures in range this frame, nearest-first order is not required
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
    /** One markable structure: its selection identity plus the distance used for priority. */
    record Mark(StructureSelection.Entry entry, double centerDistance) {
        Mark {
            Objects.requireNonNull(entry, "entry");
        }
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
                    StructureSelection.Entry.of(structure),
                    SpyglassDistance.horizontal(cameraX, cameraZ, structure)));
        }
        return new MarkFrame(marks, worldToClip, modelView, cameraX, cameraY, cameraZ);
    }

    /**
     * Adds the selected structure from its saved world anchor when the snapshot no longer lists it.
     * Its distance is measured exactly like any other mark (saved union-bounds centre, camera
     * position), keeping the ring decision on the same basis.
     */
    MarkFrame withSelection(@Nullable StructureSelection.Entry selected) {
        if (selected == null) {
            return this;
        }
        for (Mark mark : this.marks) {
            if (mark.entry().key().equals(selected.key())) {
                return this;
            }
        }
        List<Mark> extended = new ArrayList<>(this.marks.size() + 1);
        extended.addAll(this.marks);
        extended.add(new Mark(selected, selected.distanceTo(this.cameraX, this.cameraZ)));
        return new MarkFrame(
                extended, this.worldToClip, this.modelView, this.cameraX, this.cameraY, this.cameraZ);
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
