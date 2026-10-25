package dimblend.spyglass.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Live client settings for the share of real terrain fog mixed into structure meshes.
 *
 * <p>Registered by {@link SpyglassClientSetup} during mod construction as a
 * {@code ModConfig.Type.CLIENT} config, file {@code dimblend_spyglass-client.toml}. NeoForge's
 * built-in configuration screen exposes it at Mods -> DimBlend Spyglass -> Config. The renderer reads
 * the values on every pass, so an edit applies on the next frame without restarting the game.
 *
 * <p>{@code 0} removes the extra fog entirely (the pre-fog crisp meshes); {@code 1} is the same fog
 * the real terrain receives. {@link ShapeFogStrength} defines how the two values are interpolated.
 */
final class SpyglassClientConfig {
    static final double DEFAULT_NEAR_FOG_STRENGTH = 0.5;
    static final double DEFAULT_FAR_FOG_STRENGTH = 0.7;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.DoubleValue NEAR_FOG_STRENGTH = BUILDER
            .comment(
                    "Share of the real terrain fog applied to structures inside loaded terrain and at the fog edge.",
                    "0 = crisp meshes, 1 = the same fog as the real terrain.")
            .defineInRange("nearFogStrength", DEFAULT_NEAR_FOG_STRENGTH, 0.0, 1.0);
    private static final ModConfigSpec.DoubleValue FAR_FOG_STRENGTH = BUILDER
            .comment(
                    "Share of the real fog applied to far structures at 1024 blocks, ramping from nearFogStrength at the fog end.",
                    "0 = crisp meshes, 1 = the same fog as the real terrain.")
            .defineInRange("farFogStrength", DEFAULT_FAR_FOG_STRENGTH, 0.0, 1.0);
    static final ModConfigSpec SPEC = BUILDER.build();

    /** Share for structures inside loaded terrain and at the fog edge; render thread. */
    static double nearFogStrength() {
        return SPEC.isLoaded() ? NEAR_FOG_STRENGTH.get() : DEFAULT_NEAR_FOG_STRENGTH;
    }

    /** Share for far structures at 1024 blocks; render thread. */
    static double farFogStrength() {
        return SPEC.isLoaded() ? FAR_FOG_STRENGTH.get() : DEFAULT_FAR_FOG_STRENGTH;
    }

    private SpyglassClientConfig() {
    }
}
