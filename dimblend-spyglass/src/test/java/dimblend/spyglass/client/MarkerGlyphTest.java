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

    @Test
    void theSelectedMarkKeepsItsGlyphInsideBrackets() {
        assertEquals("[?]", MarkerGlyph.of(true, true));
        assertEquals("[!]", MarkerGlyph.of(false, true));
        assertEquals("?", MarkerGlyph.of(true, false));
        assertEquals("!", MarkerGlyph.of(false, false));
    }

    @Test
    void selectedGlyphsStayPrintableAscii() {
        for (String glyph : new String[] {MarkerGlyph.of(true, true), MarkerGlyph.of(false, true)}) {
            assertEquals(3, glyph.length());
            for (int i = 0; i < glyph.length(); i++) {
                assertTrue(glyph.charAt(i) >= 32 && glyph.charAt(i) < 127, "not printable ASCII: " + glyph);
            }
        }
    }
}
