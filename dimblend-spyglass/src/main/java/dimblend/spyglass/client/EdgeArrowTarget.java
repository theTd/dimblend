package dimblend.spyglass.client;

import dimblend.spyglass.structure.StructureKey;
import javax.annotation.Nullable;

/**
 * Chooses the mark the edge arrow points at.
 *
 * <p>With a selection the arrow belongs to the selected structure and to nothing else: it is drawn
 * only while that mark is outside the lens. The mark may have been skipped by the overlap rule (it
 * was not drawn), which must not affect where the arrow points — the frame still carries its
 * projected direction. With no selection the historical rule stays: the nearest {@code ?} outside
 * the lens, {@code !} never participates.
 *
 * <p>When a selection exists but its mark is missing from the frame (no snapshot yet, or a frame
 * built while the terrain fog was unusable), no arrow is drawn rather than pointing at an unrelated
 * structure.
 */
final class EdgeArrowTarget {
    static @Nullable MarkScreenFrame.Mark choose(
            MarkScreenFrame frame, @Nullable StructureKey selectedKey) {
        if (selectedKey != null) {
            MarkScreenFrame.Mark selected = frame.markWithKey(selectedKey);
            if (selected == null || selected.inLens()) {
                return null;
            }
            return selected;
        }
        MarkScreenFrame.Mark best = null;
        for (MarkScreenFrame.Mark mark : frame.marks()) {
            if (!mark.predicted() || mark.inLens()) {
                continue;
            }
            if (best == null || mark.distance() < best.distance()) {
                best = mark;
            }
        }
        return best;
    }

    private EdgeArrowTarget() {
    }
}
