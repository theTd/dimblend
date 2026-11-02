package dimblend.spyglass.client;

import java.util.List;

/**
 * The projected markers of the current GUI frame: published by {@link StructureMarkLayer} and read
 * by the HUD layers (top-of-screen distance, edge arrow) of the same frame.
 *
 * <p>Every in-range structure is present, not only the ones that were drawn: a mark skipped by the
 * overlap rule still tells the arrow layer where it is. {@code drawn} is the overlap verdict,
 * {@code inLens} the inset-rectangle test, and the {@code view*} triple is the camera-space direction
 * of the anchor (used by the arrow when the mark is behind the camera).
 */
record MarkScreenFrame(List<Mark> marks, double guiWidth, double guiHeight) {
    record Mark(
            float x,
            float y,
            boolean predicted,
            double distance,
            boolean drawn,
            boolean inLens,
            float viewX,
            float viewY,
            float viewZ) {
    }

    static final MarkScreenFrame EMPTY = new MarkScreenFrame(List.of(), 0.0, 0.0);

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
}
