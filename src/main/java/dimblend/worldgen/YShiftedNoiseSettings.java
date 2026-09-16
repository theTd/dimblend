package dimblend.worldgen;

import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;

/**
 * Rebuilds another mod's {@link NoiseGeneratorSettings} so {@link YShiftedDensity}
 * moves terrain by {@code yOffset} without forking that mod's density JSON.
 */
public final class YShiftedNoiseSettings {
    private YShiftedNoiseSettings() {
    }

    public static Holder<NoiseGeneratorSettings> wrap(
            Holder<NoiseGeneratorSettings> source,
            int yOffset,
            @Nullable Integer seaLevelOverride
    ) {
        NoiseGeneratorSettings settings = source.value();
        if (yOffset == 0 || alreadyShifted(settings, yOffset)) {
            return source;
        }
        if (settings.noiseRouter().finalDensity() instanceof YShiftedDensity shifted) {
            throw new IllegalStateException(
                    "noise settings already y-shifted by " + shifted.offset()
                            + ", cannot apply " + yOffset);
        }
        NoiseRouter router = settings.noiseRouter();
        NoiseRouter shiftedRouter = new NoiseRouter(
                router.barrierNoise(),
                router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(),
                router.lavaNoise(),
                router.temperature(),
                router.vegetation(),
                router.continents(),
                router.erosion(),
                router.depth(),
                router.ridges(),
                new YShiftedDensity(router.initialDensityWithoutJaggedness(), yOffset),
                new YShiftedDensity(router.finalDensity(), yOffset),
                router.veinToggle(),
                router.veinRidged(),
                router.veinGap());
        NoiseSettings sourceNoise = settings.noiseSettings();
        NoiseSettings shiftedNoise = NoiseSettings.create(
                YShiftWindow.minY(sourceNoise.minY(), yOffset),
                YShiftWindow.height(sourceNoise.minY(), sourceNoise.height(), yOffset),
                sourceNoise.noiseSizeHorizontal(),
                sourceNoise.noiseSizeVertical());
        NoiseGeneratorSettings shifted = new NoiseGeneratorSettings(
                shiftedNoise,
                settings.defaultBlock(),
                settings.defaultFluid(),
                shiftedRouter,
                settings.surfaceRule(),
                settings.spawnTarget(),
                seaLevelOverride != null ? seaLevelOverride.intValue() : settings.seaLevel(),
                settings.disableMobGeneration(),
                settings.aquifersEnabled(),
                settings.oreVeinsEnabled(),
                settings.useLegacyRandomSource());
        return Holder.direct(shifted);
    }

    static boolean alreadyShifted(NoiseGeneratorSettings settings, int yOffset) {
        return settings.noiseRouter().finalDensity() instanceof YShiftedDensity shifted
                && shifted.offset() == yOffset;
    }
}
