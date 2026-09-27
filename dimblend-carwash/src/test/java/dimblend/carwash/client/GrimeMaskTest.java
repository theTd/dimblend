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
