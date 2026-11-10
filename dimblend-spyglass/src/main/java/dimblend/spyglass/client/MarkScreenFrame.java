package dimblend.spyglass.client;

import dimblend.spyglass.structure.StructureKey;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The projected markers of the current GUI frame: published by {@link StructureMarkLayer} and read
 * by the HUD layers (top-of-screen distance, edge arrow) and by {@link StructureMarkInput} of the
 * same frame.
 *
 * <p>Every in-range structure is present, not only the ones that were drawn: a mark skipped by the
 * overlap rule still tells the arrow layer where it is, and the selected mark must still be
 * locatable when it was hidden. {@code drawn} is the overlap verdict, {@code inLens} the
 * inset-rectangle test, {@code halfWidth} the half width of the box that was actually drawn (wider
 * for the bracketed selection), and the {@code view*} triple is the camera-space direction of the
 * anchor (used by the arrow when the mark is behind the camera).
 *
 * <p>{@code publishedAtNanos} lets the input layer reject a stale frame: a click must hit what is
 * on screen now, at the current GUI size, not a frame left over from before a resize, a pause or a
 * hang.
 */
record MarkScreenFrame(List<Mark> marks, double guiWidth, double guiHeight, long publishedAtNanos) {
    record Mark(
            StructureSelection.Entry entry,
            float x,
            float y,
            double distance,
            boolean drawn,
            boolean inLens,
            float halfWidth,
            float viewX,
            float viewY,
            float viewZ) {
        Mark {
            Objects.requireNonNull(entry, "entry");
            if (!(halfWidth >= 0.0F)) {
                throw new IllegalArgumentException("Negative mark half width: " + halfWidth);
            }
        }

        StructureKey key() {
            return entry.key();
        }

        boolean predicted() {
            return entry.predicted();
        }
    }

    static final MarkScreenFrame EMPTY = new MarkScreenFrame(List.of(), 0.0, 0.0, 0L);

    private static MarkScreenFrame current = EMPTY;

    MarkScreenFrame {
        marks = List.copyOf(marks);
    }

    static void publish(MarkScreenFrame frame) {
        current = frame;
    }

    static MarkScreenFrame current() {
        return current;
    }

    /** Forgets the last frame, e.g. on a dimension change or logout. */
    static void reset() {
        current = EMPTY;
    }

    /**
     * A frame is usable for input only when it was published recently at the size the GUI has now.
     * The GUI dimensions are the exact framebuffer size divided by the GUI scale, the same value
     * the marker layer projects with.
     */
    boolean isFresh(long nowNanos, double width, double height, long maxAgeNanos) {
        if (publishedAtNanos <= 0L) {
            return false;
        }
        long age = nowNanos - publishedAtNanos;
        return age >= 0L
                && age <= maxAgeNanos
                && Double.compare(this.guiWidth, width) == 0
                && Double.compare(this.guiHeight, height) == 0;
    }

    /** The mark of one structure key, drawn or not; {@code null} when it is not in this frame. */
    @Nullable Mark markWithKey(StructureKey key) {
        for (Mark mark : this.marks) {
            if (mark.key().equals(key)) {
                return mark;
            }
        }
        return null;
    }

    /**
     * Index of the drawn mark under the crosshair, or {@code -1}. Only marks that were actually
     * drawn are candidates, and each uses the box that was drawn (the selection's wider one
     * included). Nearest to the screen center wins; ties keep the earlier mark.
     */
    int hit(double crosshairX, double crosshairY, double tolerancePx) {
        double[] screenX = new double[this.marks.size()];
        double[] screenY = new double[this.marks.size()];
        double[] halfWidth = new double[this.marks.size()];
        int[] index = new int[this.marks.size()];
        int count = 0;
        for (int i = 0; i < this.marks.size(); i++) {
            Mark mark = this.marks.get(i);
            if (!mark.drawn() || !Float.isFinite(mark.x()) || !Float.isFinite(mark.y())) {
                continue;
            }
            screenX[count] = mark.x();
            screenY[count] = mark.y();
            halfWidth[count] = mark.halfWidth();
            index[count] = i;
            count++;
        }
        int best = CrosshairPicking.hit(
                screenX, screenY, halfWidth, count, crosshairX, crosshairY, tolerancePx);
        return best < 0 ? -1 : index[best];
    }
}
