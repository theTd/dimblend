package dimblend.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Overflow-safe replica of vanilla's End island height field
 * ({@code DensityFunctions.EndIslandDensityFunction#getHeightValue}, 1.21.1).
 *
 * <p>Vanilla computes the origin-distance term as
 * {@code (float)(x * x + z * z)} in <em>int</em>, where {@code x = blockX / 8} and
 * {@code z = blockZ / 8}. The int multiply wraps as soon as
 * {@code x*x + z*z >= 2^31}, i.e. at a Euclidean block distance of 370,728 from
 * the End noise origin; the wrapped sum goes negative, {@code Mth.sqrt} turns it
 * into NaN, and NaN survives {@code Mth.clamp} and {@code Math.max} — the whole
 * density column becomes NaN, so {@code density > 0} is false everywhere and
 * <b>no island terrain generates at all</b>. In vanilla {@code minecraft:the_end}
 * that only matters beyond 370k blocks; in {@code dimblend:rotating} the End
 * delegate samples at the rotating dimension's absolute X, so every End band
 * past region 181 (X &ge; 370,728, at corridor Z&asymp;0) is pure void — the
 * "420k End band has no islands" report. The dead zone is an annulus: it ends
 * near 524,288 blocks where the wrapped sum turns positive again, then
 * alternates further out.</p>
 *
 * <p>This copy is identical to vanilla except that the squared distance is
 * computed in {@code long}, which cannot wrap. Within the vanilla-safe range
 * ({@code x*x + z*z < 2^31}) the two are bit-identical, so already-generated
 * chunks and the vanilla End dimension are unaffected; beyond it the remaining
 * loop math (already long/double in vanilla) keeps placing outer islands
 * exactly as it does at sub-370k distances.</p>
 */
public final class EndIslandHeight {
    private EndIslandHeight() {
    }

    /**
     * Height value of the End island field at cell coordinates {@code x = blockX / 8},
     * {@code z = blockZ / 8}. Mirrors vanilla, range {@code [-100, 80]}.
     */
    public static float height(SimplexNoise islandNoise, int x, int z) {
        int i = x / 2;
        int j = z / 2;
        int k = x % 2;
        int l = z % 2;
        // Vanilla: (float)(x * x + z * z) — int math, wraps negative past 370,728 blocks.
        float f = 100.0F - Mth.sqrt((float)((long)x * (long)x + (long)z * (long)z)) * 8.0F;
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

    /** {@code EndIslandDensityFunction#compute} mapping: height value to density. */
    public static double density(float height) {
        return ((double)height - 8.0) / 128.0;
    }
}