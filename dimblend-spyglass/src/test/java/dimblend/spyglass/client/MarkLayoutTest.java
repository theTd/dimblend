package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MarkLayoutTest {
    @Test
    void aPlainGlyphKeepsTheHistoricalSquare() {
        // One 6-pixel glyph at 2x is 12 pixels: far below the 32-pixel minimum.
        assertEquals(12, MarkLayout.scaledGlyphWidth(6));
        assertEquals(32, MarkLayout.boxWidth(12));
        assertEquals(32, MarkLayout.boxHeight());
    }

    @Test
    void theBracketedGlyphWidensTheBox() {
        // "[?]" is three 6-pixel glyphs: 36 pixels at 2x, plus 4 pixels of padding per side.
        assertEquals(44, MarkLayout.boxWidth(MarkLayout.scaledGlyphWidth(18)));
    }

    @Test
    void anEmptyGlyphStillGetsTheMinimumBox() {
        assertEquals(32, MarkLayout.boxWidth(0));
    }

    @Test
    void negativeWidthsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MarkLayout.boxWidth(-1));
        assertThrows(IllegalArgumentException.class, () -> MarkLayout.scaledGlyphWidth(-1));
    }
}
