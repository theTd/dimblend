package dimblend.client.bandrift;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The far band's lightmap: a second 16×16 {@link DynamicTexture} computed from the far lane's
 * atmosphere instead of the player's {@code ClientLevel}.
 *
 * <p>A second vanilla {@code LightTexture} instance would be useless here — it reads the same
 * level, the same player and the same darkness effects, so it would produce the same table. What
 * the far pass needs is the <em>other</em> band's light: the nether-like lanes get vanilla's
 * constant-ambient curve, the End lanes get the bright end lightmap, the sky lanes a bright day
 * table, and the dark lanes a heavily attenuated one. Only the input profile changes; the pixel
 * math is the same shape as {@code LightTexture.updateLightTexture} so the far terrain keeps the
 * familiar Minecraft light falloff.</p>
 *
 * <p>The table is rebuilt only when the lane or the gamma option changes, and
 * {@link #textureId(String)} is safe to call every frame. Binding for the Sodium pass goes through
 * {@link VolumetricRiftState#setLightTextureOverride(int)} because Sodium rebinds the main
 * lightmap in {@code RenderType.setupRenderState}; the mirage pass simply samples this texture as
 * {@code Sampler2}.</p>
 */
public final class BandLightTexture {
    /** TextureManager only needs a stable local name; it is uniquified internally. */
    private static final String REGISTRY_NAME = "dimblend_band_light";
    private static final int SIZE = 16;
    private static final int PIXELS = SIZE * SIZE;

    private static DynamicTexture texture;
    private static ResourceLocation location;
    private static String lane = "\u0000";
    private static float gamma = -1.0F;
    private static int updates;

    private BandLightTexture() {
    }

    /** Lane atmosphere feeding the lightmap curve. */
    private record Profile(float ambient, boolean bright, float skyDarken) {
    }

    private static Profile profile(@Nullable String laneName) {
        return switch (laneName == null ? "" : laneName) {
            // Nether-family lanes: vanilla's constant ambient light, no usable sky term.
            case "underground" -> new Profile(0.10F, false, 0.12F);
            case "nether" -> new Profile(0.10F, false, 0.08F);
            // End-family lanes: forceBrightLightmap, exactly like DimensionType.END_EFFECTS.
            case "end", "deeperdarker" -> new Profile(0.0F, true, 0.0F);
            case "voidscape" -> new Profile(0.0F, false, 0.02F);
            case "twilight" -> new Profile(0.0F, false, 0.35F);
            case "starlight" -> new Profile(0.0F, false, 0.20F);
            case "aether" -> new Profile(0.0F, false, 1.0F);
            default -> new Profile(0.0F, false, 1.0F);
        };
    }

    /**
     * Uploads the table if needed and returns its GL texture id, or 0 when no level is loaded.
     */
    public static int textureId(@Nullable String laneName) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0;
        }
        float currentGamma = (float) (double) minecraft.options.gamma().get();
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, false);
            location = minecraft.getTextureManager().register(REGISTRY_NAME, texture);
            lane = "\u0000";
            gamma = -1.0F;
        }
        if (!Objects.equals(lane, laneName) || gamma != currentGamma) {
            lane = laneName;
            gamma = currentGamma;
            fill(texture.getPixels(), profile(laneName), currentGamma);
            texture.upload();
            updates++;
        }
        return texture.getId();
    }

    /** Makes the far-pass Sodium shader bind this table on its LIGHT slot. */
    public static void bindForSodiumAndMirage(@Nullable String laneName) {
        VolumetricRiftState.setLightTextureOverride(textureId(laneName));
    }

    /** Back to the main lightmap for the near pass. */
    public static void restore() {
        VolumetricRiftState.setLightTextureOverride(0);
    }

    public static int updates() {
        return updates;
    }

    @Nullable
    public static ResourceLocation location() {
        return location;
    }

    public static void close() {
        DynamicTexture old = texture;
        ResourceLocation oldLocation = location;
        texture = null;
        location = null;
        lane = "\u0000";
        gamma = -1.0F;
        if (old == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (oldLocation != null && minecraft.getTextureManager() != null) {
            // release() closes the texture and drops the name mapping; calling close() directly
            // would leave the manager pointing at a dead GL id until the next resource reload.
            minecraft.getTextureManager().release(oldLocation);
        } else {
            old.close();
        }
    }

    /** One row per sky level, one column per block level, matching LightTexture's layout. */
    private static void fill(NativeImage image, Profile profile, float gammaValue) {
        float skyScale = profile.skyDarken() * 0.95F + 0.05F;
        int[] row = new int[SIZE];
        for (int sky = 0; sky < SIZE; sky++) {
            float skyLevel = curve(profile.ambient(), sky) * skyScale;
            // new Vector3f(f, f, 1.0f).lerp(white, 0.35f) * skyLevel, written out.
            float skyTint = 0.65F * profile.skyDarken() + 0.35F;
            float skyRed = skyLevel * skyTint;
            float skyGreen = skyLevel * skyTint;
            float skyBlue = skyLevel;
            for (int block = 0; block < SIZE; block++) {
                float value = curve(profile.ambient(), block);
                float green = value * ((value * 0.6F + 0.4F) * 0.6F + 0.4F);
                float blue = value * (value * value * 0.6F + 0.4F);
                float red = value;
                if (profile.bright()) {
                    red = Mth.lerp(0.25F, red, 0.99F);
                    green = Mth.lerp(0.25F, green, 1.12F);
                    blue = Mth.lerp(0.25F, blue, 1.0F);
                    red = Mth.clamp(red, 0.0F, 1.0F);
                    green = Mth.clamp(green, 0.0F, 1.0F);
                    blue = Mth.clamp(blue, 0.0F, 1.0F);
                } else {
                    red += skyRed;
                    green += skyGreen;
                    blue += skyBlue;
                    red = Mth.lerp(0.04F, red, 0.75F);
                    green = Mth.lerp(0.04F, green, 0.75F);
                    blue = Mth.lerp(0.04F, blue, 0.75F);
                    if (gammaValue > 0.0F) {
                        red = Mth.lerp(gammaValue, red, notGamma(red));
                        green = Mth.lerp(gammaValue, green, notGamma(green));
                        blue = Mth.lerp(gammaValue, blue, notGamma(blue));
                    }
                    red = Mth.lerp(0.04F, Mth.clamp(red, 0.0F, 1.0F), 0.75F);
                    green = Mth.lerp(0.04F, Mth.clamp(green, 0.0F, 1.0F), 0.75F);
                    blue = Mth.lerp(0.04F, Mth.clamp(blue, 0.0F, 1.0F), 0.75F);
                }
                int r = (int) (Mth.clamp(red, 0.0F, 1.0F) * 255.0F);
                int g = (int) (Mth.clamp(green, 0.0F, 1.0F) * 255.0F);
                int b = (int) (Mth.clamp(blue, 0.0F, 1.0F) * 255.0F);
                row[block] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
            for (int block = 0; block < SIZE; block++) {
                image.setPixelRGBA(block, sky, row[block]);
            }
        }
    }

    /** {@code LightTexture.getBrightness} with a raw ambient value instead of a DimensionType. */
    private static float curve(float ambientLight, int level) {
        float f = level / 15.0F;
        float curved = f / (4.0F - 3.0F * f);
        return Mth.lerp(ambientLight, curved, 1.0F);
    }

    private static float notGamma(float value) {
        float inverted = 1.0F - value;
        return 1.0F - inverted * inverted * inverted * inverted;
    }

    /** Total pixels of the last upload; the table is always 16×16. */
    public static int pixels() {
        return PIXELS;
    }
}
