package dimblend.worldgen;

import java.util.OptionalInt;
import javax.annotation.Nullable;
import net.minecraft.util.StringRepresentable;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;

/**
 * Rebuilds another mod's {@link NoiseGeneratorSettings} so the terrain moves by
 * {@code yOffset} without forking that mod's density JSON.
 *
 * <p>The Y shift must sit <em>below</em> every {@code minecraft:interpolated} marker, not
 * above the whole tree. Vanilla {@code NoiseChunk} rewrites interpolated markers into
 * {@code NoiseInterpolator}s whose corner values are sampled at {@code cellStartBlockY};
 * the interpolated value is only produced for {@code FunctionContext == NoiseChunk}.
 * A wrapper above the marker (the pre-fix shape, {@code YShiftedDensity(whole tree)})
 * samples it with a shifted delegate context instead, which silently falls back to
 * {@code noiseFiller.compute} — the per-corner interpolation is bypassed and the band's
 * terrain is drawn from uninterpolated noise, a completely different surface from the
 * source dimension (empirically ~15-30% of cells differed while biomes stayed identical).
 * Wrapping the leaves below the marker keeps the corner sampling points and weights
 * identical to the source dimension: corners land on {@code cellStartBlockY + offset},
 * exactly the source's corner grid, so the band renders the source terrain one-to-one.
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
        OptionalInt existing = existingShiftOffset(settings.noiseRouter().finalDensity());
        if (existing.isPresent()) {
            throw new IllegalStateException(
                    "noise settings already y-shifted by " + existing.getAsInt()
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
                shifted(router.initialDensityWithoutJaggedness(), yOffset),
                shifted(router.finalDensity(), yOffset),
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

    /**
     * Pushes the Y shift below every interpolation marker: each node whose subtree contains
     * neither an interpolated marker nor an already-placed shift wrapper gets wrapped in
     * {@link YShiftedDensity}, so the shift applies exactly once per Y-sensitive leaf while
     * interpolation nodes and their ancestors stay unwrapped above their shifted arguments.
     * The shift check is what keeps the walk idempotent: rebuilding parents around freshly
     * wrapped children would otherwise re-wrap every ancestor (the walk is post-order, so
     * each level above a wrapped leaf would see a subtree that only "contains a shift"),
     * accumulating offsets per nesting depth and moving terrain by a multiple of the offset.
     * Nested interpolated markers need no special case: the walk keeps the shift below the
     * innermost marker, whose corners already sample source-dimension values, so the outer
     * marker then interpolates those same values (neither wrapped tree nests markers today).
     * Callers must not pass trees shifted by a different offset (the wrap-time guards
     * cover that); {@link YShiftedDensity} nodes themselves are passed through untouched.
     */
    static DensityFunction shifted(DensityFunction function, int offset) {
        return function.mapAll(node -> {
            if (node instanceof YShiftedDensity) {
                return node;
            }
            return containsInterpolated(node) || existingShiftOffset(node).isPresent()
                    ? node
                    : new YShiftedDensity(node, offset);
        });
    }

    private static boolean containsInterpolated(DensityFunction function) {
        boolean[] found = {false};
        function.mapAll(node -> {
            if (node instanceof DensityFunctions.MarkerOrMarked marker
                    && "interpolated".equals(markerTypeName(marker))) {
                found[0] = true;
            }
            return node;
        });
        return found[0];
    }

    /** Marker's type enum is a protected nested class; identify it by its serial name. */
    private static String markerTypeName(DensityFunctions.MarkerOrMarked marker) {
        return ((StringRepresentable) marker.type()).getSerializedName();
    }

    static boolean alreadyShifted(NoiseGeneratorSettings settings, int yOffset) {
        return existingShiftOffset(settings.noiseRouter().finalDensity())
                .stream().anyMatch(existing -> existing == yOffset);
    }

    /** Offset of any {@link YShiftedDensity} leaf wrapper in the tree, empty when none. */
    static OptionalInt existingShiftOffset(DensityFunction function) {
        OptionalInt[] found = {OptionalInt.empty()};
        function.mapAll(node -> {
            if (node instanceof YShiftedDensity shifted && found[0].isEmpty()) {
                found[0] = OptionalInt.of(shifted.offset());
            }
            return node;
        });
        return found[0];
    }
}
