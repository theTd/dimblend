package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the far-End island void: vanilla's
 * {@code DensityFunctions.EndIslandDensityFunction.getHeightValue} squares
 * {@code blockX/8, blockZ/8} in int, which wraps negative past a Euclidean
 * radius of 370,728 blocks ({@code (x/8)^2 + (z/8)^2 >= 2^31}); the NaN that
 * follows kills every density column, so End bands of the rotating dimension
 * past region 181 generate no islands. {@link EndIslandHeight} must be
 * bit-identical inside the safe range and finite (with islands) beyond it.
 */
class EndIslandHeightTest {
    /** Same construction as the vanilla EndIslandDensityFunction constructor. */
    private static SimplexNoise islandNoise() {
        RandomSource random = new LegacyRandomSource(0L);
        random.consumeCount(17292);
        return new SimplexNoise(random);
    }

    /**
     * Verbatim vanilla {@code getHeightValue} (1.21.1) with the overflowing int
     * math intact — the spec EndIslandHeight reproduces below the cliff.
     */
    private static float vanillaSpecHeight(SimplexNoise islandNoise, int x, int z) {
        int i = x / 2;
        int j = z / 2;
        int k = x % 2;
        int l = z % 2;
        float f = 100.0F - Mth.sqrt((float)(x * x + z * z)) * 8.0F;
        f = Mth.clamp(f, -100.0F, 80.0F);
        for (int i1 = -12; i1 <= 12; i1++) {
            for (int j1 = -12; j1 <= 12; j1++) {
                long k1 = i + i1;
                long l1 = j + j1;
                if (k1 * k1 + l1 * l1 > 4096L && islandNoise.getValue((double)k1, (double)l1) < -0.9F) {
                    float f1 = (Mth.abs((float)k1) * 3439.0F + Mth.abs((float)l1) * 147.0F) % 13.0F + 9.0F;
                    float f2 = k - i1 * 2;
                    float f3 = l - j1 * 2;
                    float f4 = 100.0F - Mth.sqrt(f2 * f2 + f3 * f3) * f1;
                    f4 = Mth.clamp(f4, -100.0F, 80.0F);
                    f = Math.max(f, f4);
                }
            }
        }
        return f;
    }

    @Test
    void bitIdenticalToVanillaWhileIntSumDoesNotWrap() {
        SimplexNoise noise = islandNoise();
        int[] cells = {-46340, -32767, -1000, -12, -1, 0, 1, 7, 128, 1024, 10000, 32767, 46340};
        int checked = 0;
        for (int x : cells) {
            for (int z : cells) {
                if ((long)x * x + (long)z * z >= (1L << 31)) {
                    continue; // vanilla int math already wraps here
                }
                assertEquals(vanillaSpecHeight(noise, x, z), EndIslandHeight.height(noise, x, z), () ->
                        "divergence from vanilla at cell " + x + "," + z);
                checked++;
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    void vanillaMathNanAtCliffWhileFixedMathStaysFinite() {
        SimplexNoise noise = islandNoise();
        // blockX = 370,728 (first wrap) and blockX = 420,000 (the reported band):
        // vanilla density is NaN there, which renders as a solid column of air.
        assertTrue(Float.isNaN(vanillaSpecHeight(noise, 370728 / 8, 0)));
        assertTrue(Float.isNaN(vanillaSpecHeight(noise, 420000 / 8, 0)));
        for (int blockX : new int[] {370728, 420000, 1_000_000}) {
            float height = EndIslandHeight.height(noise, blockX / 8, 0);
            assertFalse(Float.isNaN(height), "NaN at blockX=" + blockX);
            assertTrue(height >= -100.0F && height <= 80.0F, "out of vanilla range at blockX=" + blockX);
        }
    }

    @Test
    void farEndBandPlacesIslandsNearCorridor() {
        SimplexNoise noise = islandNoise();
        // Region 205 (X 419,840..421,888) is the reported void band; scan it the
        // way DimBlendCommands.sampleHeights does, at corridor Z in [0, 256).
        int islandColumns = 0;
        float peak = -100.0F;
        for (int dx = 0; dx < 2048; dx += 16) {
            for (int dz = 0; dz < 256; dz += 16) {
                int blockX = 205 * 2048 + dx;
                float height = EndIslandHeight.height(noise, blockX / 8, dz / 8);
                assertFalse(Float.isNaN(height));
                peak = Math.max(peak, height);
                if (height > 0.0F) {
                    islandColumns++;
                }
            }
        }
        assertTrue(islandColumns > 0, "no island columns in far End band, peak=" + peak);
        assertTrue(peak > 40.0F, "no island peak in far End band, peak=" + peak);
    }
}