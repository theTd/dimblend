package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkerGlyphTest {
    @Test
    void predictedStructuresUseAQuestionMarkAndSavedOnesAnExclamationMark() {
        assertEquals("?", MarkerGlyph.of(true));
        assertEquals("!", MarkerGlyph.of(false));
    }

    @Test
    void bothGlyphsAreSingleAsciiCharacters() {
        for (String glyph : new String[] {MarkerGlyph.of(true), MarkerGlyph.of(false)}) {
            assertEquals(1, glyph.length());
            assertTrue(glyph.charAt(0) < 128, "the glyph must be ASCII: " + glyph);
        }
    }
}
