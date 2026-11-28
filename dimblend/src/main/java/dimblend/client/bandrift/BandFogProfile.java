package dimblend.client.bandrift;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;

/**
 * Atmosphere of the band on the far side of a seam: fog colour/plane, lightmap tint profile and
 * far-sky palette. Pure data plus a save/restore pair for {@link RenderSystem}'s fog state, so the
 * second terrain pass can render with the other band's fog and leave the first pass untouched.
 *
 * <p>Everything is keyed by {@code BandLayout.laneName} (the same strings the server sends in
 * {@link dimblend.mirage.BandSeamInfoPayload}), never by biome or dimension lookups: the far band
 * is another delegate of the <em>same</em> dimension, so its atmosphere only exists as this table.
 * Values are deliberately conservative — thick, dark fog for the cave-like lanes, bright haze for
 * the sky lanes — because the far pass draws real chunks that were lit by a different sun.</p>
 */
public final class BandFogProfile {
    /** Fog curve in blocks. Alpha is always 1 for terrain fog. */
    public record Profile(float red, float green, float blue, float start, float end) {
        public float[] color() {
            return new float[]{this.red, this.green, this.blue, 1.0F};
        }
    }

    /** Far-sky gradient plus star/ember density, consumed by {@code dimblend:band_far_sky}. */
    public record SkyStyle(float zenithRed, float zenithGreen, float zenithBlue,
                           float horizonRed, float horizonGreen, float horizonBlue,
                           float starDensity) {
    }

    private static final float[] SAVED_COLOR = new float[4];
    private static float savedStart;
    private static float savedEnd = 1.0F;
    private static FogShape savedShape = FogShape.SPHERE;
    private static boolean saved;

    private BandFogProfile() {
    }

    /** Fog for {@code laneName}; {@code renderDistanceBlocks} is the player's view distance. */
    public static Profile forLane(@Nullable String laneName, float renderDistanceBlocks) {
        float distance = Math.max(64.0F, renderDistanceBlocks);
        return switch (laneName == null ? "" : laneName) {
            case "underground" -> new Profile(0.055F, 0.047F, 0.040F, 6.4F, 64.0F);
            case "nether" -> new Profile(0.070F, 0.030F, 0.018F, 8.0F, 58.0F);
            case "end" -> new Profile(0.045F, 0.020F, 0.085F, 0.55F * distance, distance);
            case "deeperdarker" -> new Profile(0.015F, 0.030F, 0.055F, 5.0F, 44.0F);
            case "twilight" -> new Profile(0.045F, 0.115F, 0.095F, 0.40F * distance, 0.92F * distance);
            case "starlight" -> new Profile(0.030F, 0.040F, 0.105F, 0.25F * distance, 0.95F * distance);
            case "voidscape" -> new Profile(0.0F, 0.0F, 0.0F, 2.0F, 18.0F);
            case "surface" -> new Profile(0.72F, 0.83F, 1.0F, 0.92F * distance, distance);
            case "aether" -> new Profile(0.86F, 0.93F, 1.0F, 0.90F * distance, distance);
            default -> new Profile(0.55F, 0.70F, 1.0F, 0.90F * distance, distance);
        };
    }

    /** Sky palette for {@code laneName}; see {@link SkyStyle}. */
    public static SkyStyle skyForLane(@Nullable String laneName) {
        return switch (laneName == null ? "" : laneName) {
            case "underground" -> new SkyStyle(0.020F, 0.018F, 0.016F, 0.100F, 0.070F, 0.040F, 0.02F);
            case "nether" -> new SkyStyle(0.060F, 0.020F, 0.010F, 0.350F, 0.100F, 0.030F, 0.08F);
            case "end" -> new SkyStyle(0.020F, 0.005F, 0.040F, 0.100F, 0.030F, 0.180F, 0.55F);
            case "deeperdarker" -> new SkyStyle(0.005F, 0.010F, 0.020F, 0.020F, 0.060F, 0.100F, 0.20F);
            case "twilight" -> new SkyStyle(0.020F, 0.060F, 0.090F, 0.090F, 0.200F, 0.160F, 0.25F);
            case "starlight" -> new SkyStyle(0.010F, 0.015F, 0.050F, 0.050F, 0.060F, 0.160F, 0.75F);
            case "voidscape" -> new SkyStyle(0.0F, 0.0F, 0.0F, 0.010F, 0.010F, 0.020F, 0.10F);
            case "aether" -> new SkyStyle(0.550F, 0.750F, 1.0F, 0.950F, 0.980F, 1.0F, 0.0F);
            default -> new SkyStyle(0.300F, 0.550F, 0.950F, 0.720F, 0.860F, 1.0F, 0.0F);
        };
    }

    /** Player view distance in blocks (16 per chunk), floored at 64. */
    public static float renderDistanceBlocks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null) {
            return 128.0F;
        }
        return Math.max(64.0F, minecraft.options.getEffectiveRenderDistance() * 16.0F);
    }

    /** Saves the current shader fog and applies {@code profile}. Idempotent while active. */
    public static void applyTemporary(Profile profile) {
        if (!saved) {
            float[] current = RenderSystem.getShaderFogColor();
            System.arraycopy(current, 0, SAVED_COLOR, 0, 4);
            savedStart = RenderSystem.getShaderFogStart();
            savedEnd = RenderSystem.getShaderFogEnd();
            savedShape = RenderSystem.getShaderFogShape();
            saved = true;
        }
        RenderSystem.setShaderFogColor(profile.red(), profile.green(), profile.blue(), 1.0F);
        RenderSystem.setShaderFogStart(profile.start());
        RenderSystem.setShaderFogEnd(Math.max(profile.end(), profile.start() + 1.0E-3F));
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }

    /** Restores the near band's fog exactly as {@code FogRenderer} left it. */
    public static void restore() {
        if (!saved) {
            return;
        }
        saved = false;
        RenderSystem.setShaderFogColor(SAVED_COLOR[0], SAVED_COLOR[1], SAVED_COLOR[2], SAVED_COLOR[3]);
        RenderSystem.setShaderFogStart(savedStart);
        RenderSystem.setShaderFogEnd(savedEnd);
        RenderSystem.setShaderFogShape(savedShape);
    }

    public static boolean overridden() {
        return saved;
    }

    /** One-line fog health for {@code /dimblendrift diag}. */
    public static String describe(@Nullable String laneName) {
        Profile profile = forLane(laneName, renderDistanceBlocks());
        return String.format(Locale.ROOT, "%s fog=%.3f/%.3f/%.3f %.1f..%.1f",
                laneName == null ? "unknown" : laneName,
                profile.red(), profile.green(), profile.blue(), profile.start(), profile.end());
    }
}
