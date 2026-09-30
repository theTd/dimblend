package dimblend.mixin.coldsweat;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;

import dimblend.DimBlendRegistries;
import net.minecraft.world.entity.LivingEntity;

/**
 * Keeps the Serene Seasons offset out of Cold Sweat's temperature in the rotating dimension,
 * the second half of the no-winter-freeze fix after
 * {@link dimblend.mixin.sereneseasons.SeasonHooksTemperatureMixin}.
 *
 * <p>Water freezing never reaches Serene Seasons' hook while Cold Sweat runs with
 * {@code "Custom Freezing Behavior" = true} (its default). Cold Sweat's
 * {@code MixinFreezingWater} cancels {@code Biome.shouldFreeze(LevelReader, BlockPos, boolean)}
 * at HEAD for every water block and decides with
 * {@code WorldHelper.shouldFreeze}: {@code getRoughTemperatureAt + getWaterTemperatureAt <= 0}.
 * Serene Seasons' {@code warmEnoughToRain} redirect lives deeper in that method body, so it
 * never runs. Cold Sweat also replaces ice melting ({@code IceBlock.randomTick} via
 * {@code WorldHelper.shouldMelt}, same temperature) and cancels Serene Seasons' ice melt.
 * The winter push comes from {@code SereneSeasonsTempModifier}, one of the world-temperature
 * modifiers. It reads Serene Seasons' dimension whitelist itself and adds the
 * {@code [Compatibility.Seasons] Winter} offset (default -0.4/-0.6/-0.4 MC units), so
 * patching {@code SeasonHooks} alone leaves winter ice forming, and not melting, in the
 * rotating dimension.
 *
 * <p>This HEAD inject returns the identity function for an entity in the rotating dimension,
 * the same result Cold Sweat returns for a dimension missing from the whitelist.
 * Every Cold Sweat reading there goes season-neutral. That covers the freeze and melt checks
 * (their dummy entity sits in the checked level) and the per-segment
 * {@code getRoughTemperatureAt} cache, which entity-climate and other callers share, so a
 * freeze-only exemption would read seasonal values back out of that cache. Player and mob body
 * temperature in the rotating dimension also stop following the season. This matches
 * {@code SeasonHooksTemperatureMixin}: the rotating dimension has no seasonal temperature.
 * Season colors, crop fertility and weather frequency are untouched. Ice left from earlier
 * winters thaws from its edges inward on Cold Sweat's own melt tick wherever the non-seasonal
 * temperature is above zero.
 *
 * <p>String target and {@code @Local} capture: Cold Sweat is not on the compile classpath,
 * and {@code Temperature$Trait} (the second parameter) is a Cold Sweat type, so the handler
 * leaves out the target's parameters and captures only the {@link LivingEntity}. The class is
 * loaded only when Cold Sweat registers its Serene Seasons modifier (Serene Seasons present),
 * so this mixin applies exactly when both mods are installed.
 *
 * <p>Drift note: signature verified against Cold Sweat 2.4.2 with {@code javap}:
 * {@code public Function<Double, Double> calculate(LivingEntity, Temperature$Trait)}. A rename
 * or re-signature fails at apply time ({@code defaultRequire = 1}) instead of quietly bringing
 * winter freezing back. Check against the new jar before widening the {@code cold_sweat}
 * version range in {@code neoforge.mods.toml}.
 */
@Mixin(targets = "com.momosoftworks.coldsweat.api.temperature.modifier.compat.SereneSeasonsTempModifier",
        remap = false)
public abstract class SereneSeasonsTempModifierMixin {

    @Inject(
            method = "calculate(Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lcom/momosoftworks/coldsweat/api/util/Temperature$Trait;)Ljava/util/function/Function;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void dimblend$noSeasonalTemperatureInRotating(
            CallbackInfoReturnable<Function<Double, Double>> cir,
            @Local(argsOnly = true) LivingEntity entity) {
        if (DimBlendRegistries.ROTATING_LEVEL.equals(entity.level().dimension())) {
            cir.setReturnValue(Function.identity());
        }
    }
}
