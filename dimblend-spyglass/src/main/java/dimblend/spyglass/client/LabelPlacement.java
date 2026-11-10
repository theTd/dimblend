package dimblend.spyglass.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy overlap rule for structure labels: a preferred label (the selected mark) first, then the
 * nearest structure, and a label that would touch an already placed one is skipped for this frame.
 *
 * <p>Priority by distance rather than by closeness to the crosshair keeps the choice stable while the
 * player pans: the distances barely change between frames, so the same label wins every frame and
 * labels do not flicker in and out when two structures line up. A preferred label is always placed
 * before the rest, so the selection is never hidden by an unselected mark. Ties keep the input order
 * (the sort is stable).
 */
final class LabelPlacement {
    /** Minimum free gap between two placed labels, in GUI pixels. */
    static final float GAP = 2.0F;

    /** One label rectangle in GUI coordinates. */
    record Candidate<T>(
            T label, double distance, float left, float top, int width, int height, boolean preferred) {
        /** A label of the given size horizontally centered on {@code anchorX}, its bottom edge at {@code bottomY}. */
        static <T> Candidate<T> above(
                T label, double distance, float anchorX, float bottomY, int width, int height) {
            return above(label, distance, anchorX, bottomY, width, height, false);
        }

        /** As above, but placed before every non-preferred label regardless of distance. */
        static <T> Candidate<T> above(
                T label,
                double distance,
                float anchorX,
                float bottomY,
                int width,
                int height,
                boolean preferred) {
            return new Candidate<>(
                    label, distance, anchorX - width * 0.5F, bottomY - height, width, height, preferred);
        }

        boolean overlaps(Candidate<?> other) {
            return this.left < other.left + other.width + GAP
                    && other.left < this.left + this.width + GAP
                    && this.top < other.top + other.height + GAP
                    && other.top < this.top + this.height + GAP;
        }
    }

    /** The labels to draw: preferred first, then nearest first, none within {@link #GAP} of another. */
    static <T> List<Candidate<T>> place(List<Candidate<T>> candidates) {
        List<Candidate<T>> ordered = new ArrayList<>(candidates);
        ordered.sort((first, second) -> {
            if (first.preferred() != second.preferred()) {
                return first.preferred() ? -1 : 1;
            }
            return Double.compare(first.distance(), second.distance());
        });
        List<Candidate<T>> placed = new ArrayList<>(ordered.size());
        for (Candidate<T> candidate : ordered) {
            if (placed.stream().noneMatch(candidate::overlaps)) {
                placed.add(candidate);
            }
        }
        return placed;
    }

    private LabelPlacement() {
    }
}
