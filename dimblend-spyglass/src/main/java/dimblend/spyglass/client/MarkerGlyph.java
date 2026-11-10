package dimblend.spyglass.client;

/**
 * The one-glyph marker of a structure: {@code ?} when the outline was replayed from the seed for a
 * chunk the world has not generated, {@code !} when the blocks were read from a saved chunk.
 *
 * <p>The selected mark is drawn in brackets ({@code [?]}, {@code [!]}). The selection itself is
 * keyed by {@link dimblend.spyglass.structure.StructureKey}, so a predicted structure that later
 * becomes a saved one keeps its selection while its glyph changes from {@code ?} to {@code !}.
 *
 * <p>Pure and intentionally tiny: every glyph is ASCII so every shipped font can draw it, and no
 * translation is involved.
 */
final class MarkerGlyph {
    static final String PREDICTED = "?";
    static final String SAVED = "!";

    static String of(boolean predicted) {
        return predicted ? PREDICTED : SAVED;
    }

    /** The full text drawn for a mark: plain glyph, or the glyph in brackets when selected. */
    static String of(boolean predicted, boolean selected) {
        String glyph = of(predicted);
        return selected ? "[" + glyph + "]" : glyph;
    }

    private MarkerGlyph() {
    }
}
