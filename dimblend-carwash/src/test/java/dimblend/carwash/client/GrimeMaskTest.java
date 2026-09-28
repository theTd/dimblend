package dimblend.carwash.client;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GrimeMaskTest {

    private static final int OPAQUE = 0xFF336699;

    private static int[] opaque(int size) {
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, OPAQUE);
        return pixels;
    }

    private static int kept(int[] pixels, int fromRow, int toRow, int width) {
        int kept = 0;
        for (int y = fromRow; y < toRow; y++) {
            for (int x = 0; x < width; x++) {
                if (pixels[y * width + x] != 0) {
                    kept++;
                }
            }
        }
        return kept;
    }

    @Test
    void removesExactlyEightyPercentOfPixels() {
        int[] out = GrimeMask.apply(opaque(16), 16, 16, 42L, 0.8, GrimeMask.Clear.NONE);
        // 256 × 0.8 = 204.8 → 去 205，留 51
        assertEquals(51, kept(out, 0, 16, 16));
    }

    @Test
    void ladderRunsFromNinetyToTwentyPercentOverSevenLevels() {
        assertEquals(0.9, GrimeMask.ladderFraction(0.9, 0.2, 1, 7), 1e-9);
        assertEquals(0.55, GrimeMask.ladderFraction(0.9, 0.2, 4, 7), 1e-9);
        assertEquals(0.2, GrimeMask.ladderFraction(0.9, 0.2, 7, 7), 1e-9);
        assertEquals(0.5, GrimeMask.ladderFraction(0.5, 0.5, 1, 1), 1e-9);
    }

    @Test
    void firstAndLastDirtLevelsKeepTenAndEightyPercent() {
        // 256 × 0.9 = 230.4 → 去 230 留 26；256 × 0.2 = 51.2 → 去 51 留 205
        assertEquals(26, kept(GrimeMask.apply(opaque(16), 16, 16, 5L, 0.9, GrimeMask.Clear.NONE), 0, 16, 16));
        assertEquals(205, kept(GrimeMask.apply(opaque(16), 16, 16, 5L, 0.2, GrimeMask.Clear.NONE), 0, 16, 16));
        // 碎石 80%
        assertEquals(51, kept(GrimeMask.apply(opaque(16), 16, 16, 5L, 0.8, GrimeMask.Clear.NONE), 0, 16, 16));
    }

    @Test
    void keptPixelsKeepTheirSourceColour() {
        int[] source = new int[256];
        for (int i = 0; i < source.length; i++) {
            source[i] = 0xFF000000 | i;
        }
        int[] out = GrimeMask.apply(source, 16, 16, 7L, 0.8, GrimeMask.Clear.NONE);
        for (int i = 0; i < out.length; i++) {
            assertFalse(out[i] != 0 && out[i] != source[i]);
        }
    }

    @Test
    void sameSeedSamePattern_differentSeedDifferentPattern() {
        int[] a = GrimeMask.apply(opaque(16), 16, 16, 1L, 0.8, GrimeMask.Clear.NONE);
        int[] b = GrimeMask.apply(opaque(16), 16, 16, 1L, 0.8, GrimeMask.Clear.NONE);
        int[] c = GrimeMask.apply(opaque(16), 16, 16, 2L, 0.8, GrimeMask.Clear.NONE);
        assertArrayEquals(a, b);
        assertFalse(Arrays.equals(a, c));
    }

    @Test
    void lowerHalfClearKeepsOnlyTopRows() {
        int[] out = GrimeMask.apply(opaque(16), 16, 16, 3L, 0.0, GrimeMask.Clear.LOWER_HALF);
        assertEquals(128, kept(out, 0, 8, 16));
        assertEquals(0, kept(out, 8, 16, 16));
    }

    @Test
    void upperHalfClearKeepsOnlyBottomRows() {
        int[] out = GrimeMask.apply(opaque(16), 16, 16, 3L, 0.8, GrimeMask.Clear.UPPER_HALF);
        assertEquals(0, kept(out, 0, 8, 16));
    }

    @Test
    void sourceArrayIsNotModified() {
        int[] source = opaque(16);
        GrimeMask.apply(source, 16, 16, 9L, 0.8, GrimeMask.Clear.LOWER_HALF);
        assertArrayEquals(opaque(16), source);
    }
}
