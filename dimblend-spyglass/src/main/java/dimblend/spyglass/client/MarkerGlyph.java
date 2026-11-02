package dimblend.spyglass.client;

/**
 * The one-glyph marker of a structure: {@code ?} when the outline was replayed from the seed for a
 * chunk the world has not generated, {@code !} when the blocks were read from a saved chunk.
 *
 * <p>Pure and intentionally tiny: the two glyphs are ASCII so every shipped font can draw them at
 * the same width, and no translation is involved.
 */
final class MarkerGlyph {
    static String of(boolean predicted) {
        return predicted ? "?" : "!";
    }

    private MarkerGlyph() {
    }
}
