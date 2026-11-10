package dimblend.spyglass.client;

/**
 * GUI-pixel box around one mark glyph.
 *
 * <p>An unselected {@code ?} or {@code !} keeps the historical fixed square
 * ({@link #MIN_BOX}). The selection brackets ({@code [?]}, {@code [!]}) are three glyphs wide, so
 * the box grows with the scaled text instead of clipping it. The same width is used for drawing,
 * for the overlap rule and for the click test, so the player can always click what was drawn.
 *
 * <p>Pure arithmetic; the font only supplies the unscaled glyph width.
 */
final class MarkLayout {
    /** Smallest box, in GUI pixels: one doubled glyph plus its padding. */
    static final int MIN_BOX = 32;
    /** The glyph is drawn at this multiple of the font size. */
    static final float GLYPH_SCALE = 2.0F;
    /** Free pixels on each side between the scaled glyph and the box edge. */
    static final int GLYPH_PADDING = 4;

    /** Width of a glyph drawn at {@link #GLYPH_SCALE}, in GUI pixels. */
    static int scaledGlyphWidth(int unscaledGlyphWidth) {
        if (unscaledGlyphWidth < 0) {
            throw new IllegalArgumentException("Negative glyph width: " + unscaledGlyphWidth);
        }
        return Math.round(unscaledGlyphWidth * GLYPH_SCALE);
    }

    /** Box width for a glyph already scaled by {@link #GLYPH_SCALE}, in GUI pixels. */
    static int boxWidth(int scaledGlyphWidth) {
        if (scaledGlyphWidth < 0) {
            throw new IllegalArgumentException("Negative scaled glyph width: " + scaledGlyphWidth);
        }
        return Math.max(MIN_BOX, scaledGlyphWidth + 2 * GLYPH_PADDING);
    }

    /** Marks keep the historical fixed height; only the width grows with the glyph. */
    static int boxHeight() {
        return MIN_BOX;
    }

    private MarkLayout() {
    }
}
