package dimblend.mixin.sereneseasons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.season.SeasonHooks;

/**
 * Keeps Serene Seasons' seasonal temperature shift out of the rotating dimension.
 *
 * <p>Serene Seasons has no per-dimension freeze switch: once a dimension is in
 * {@code dimension_settings.whitelisted_dimensions}, winter pushes every biome with base
 * temperature &le; 0.8 below the 0.15 freeze point ({@code biome_temp_adjustment}), which is
 * what drives {@code Biome.shouldFreeze}/{@code shouldSnow}/precipitation type via Serene
 * Seasons' own redirects. The rotating dimension reuses shared vanilla/modded biomes, so the
 * biome-level {@code sereneseasons:blacklisted_biomes} tag is not an option either (it would
 * disable seasons for the same biomes in the overworld).
 *
 * <p>This HEAD inject makes {@link SeasonHooks#getBiomeTemperature(Level, Holder, BlockPos)}
 * return the biome's base temperature for the rotating dimension only. Every snow/ice/melt
 * decision funnels through this overload (the {@code LevelReader} overload delegates here for
 * real levels), so freezing, snow cover and seasonal melting in the rotating dimension fall
 * back to vanilla per-biome behavior. Effects that do not consult temperature — grass/foliage
 * colors, crop fertility, weather frequency — stay seasonal there, and all other whitelisted
 * dimensions are untouched.
 *
 * <p>{@link Biome#getBaseTemperature()} is used instead of {@code getTemperature(BlockPos)}
 * (private in vanilla; Serene Seasons opens it with its own accesstransformer, invisible to
 * javac here). Skipping the position-aware part drops two vanilla nuances in the rotating
 * dimension, both acceptable for the "no freezing" intent: the Y&gt;80 altitude cooling term
 * (low-base-temperature biomes like windswept_hills/taiga freeze at high altitude in vanilla)
 * and the FROZEN modifier's thaw patches (frozen_ocean freezes fully instead of keeping
 * scattered liquid spots). Base temperature decides everywhere else, so the freeze outcome
 * still matches vanilla per-biome behavior.
 *
 * <p>Drift note: target signature verified against
 * {@code libs/SereneSeasons-neoforge-1.21.1-10.1.0.3.jar}
 * ({@code getBiomeTemperature(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;
 * Lnet/minecraft/core/BlockPos;)F}). A Serene Seasons update that renames or re-signatures it
 * makes this inject fail loudly at apply time (defaultRequire = 1) instead of silently
 * re-enabling freezing — re-verify against the new jar, then refresh {@code libs/}.
 */
@Mixin(value = SeasonHooks.class, remap = false)
public abstract class SeasonHooksTemperatureMixin {

    @Inject(
            method = "getBiomeTemperature(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void dimblend$rawBiomeTemperatureInRotating(
            Level level, Holder<Biome> biome, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (DimBlendRegistries.ROTATING_LEVEL.equals(level.dimension())) {
            cir.setReturnValue(biome.value().getBaseTemperature());
        }
    }
}
