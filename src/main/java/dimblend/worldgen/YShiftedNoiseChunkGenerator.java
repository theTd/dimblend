package dimblend.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * Vanilla noise generator that wraps another mod's {@link NoiseGeneratorSettings} in code.
 * The wrapped settings' final and initial density are evaluated through {@link YShiftedDensity},
 * so the source dimension's terrain renders shifted up by {@code y_offset} blocks; the surface
 * rule, default block/fluid, noise envelope and every other router component are reused from
 * the source holder, so upstream data changes apply automatically and nothing is forked.
 *
 * <p>The wrapped value is held as a direct holder: it exists only at runtime, so codec
 * serialization records the source settings key plus the offsets and the wrapper is rebuilt
 * on decode.
 */
public final class YShiftedNoiseChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<YShiftedNoiseChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.sourceSettings),
            Codec.INT.fieldOf("y_offset").forGetter(generator -> generator.yOffset),
            Codec.INT.optionalFieldOf("sea_level").forGetter(generator -> Optional.ofNullable(generator.seaLevelOverride))
    ).apply(instance, YShiftedNoiseChunkGenerator::new));

    private final Holder<NoiseGeneratorSettings> sourceSettings;
    private final int yOffset;
    @Nullable
    private final Integer seaLevelOverride;

    public YShiftedNoiseChunkGenerator(
            BiomeSource biomeSource,
            Holder<NoiseGeneratorSettings> sourceSettings,
            int yOffset,
            Optional<Integer> seaLevel
    ) {
        this(biomeSource, sourceSettings, yOffset, seaLevel.orElse(null));
    }

    private YShiftedNoiseChunkGenerator(
            BiomeSource biomeSource,
            Holder<NoiseGeneratorSettings> sourceSettings,
            int yOffset,
            @Nullable Integer seaLevelOverride
    ) {
        super(biomeSource, wrap(sourceSettings, yOffset, seaLevelOverride));
        this.sourceSettings = sourceSettings;
        this.yOffset = yOffset;
        this.seaLevelOverride = seaLevelOverride;
    }

    /** Key of the wrapped source settings, e.g. twilightforest:twilight_noise_gen. Empty for direct holders. */
    public Optional<ResourceKey<NoiseGeneratorSettings>> sourceSettingsKey() {
        return this.sourceSettings.unwrapKey();
    }

    public int yOffset() {
        return this.yOffset;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private static Holder<NoiseGeneratorSettings> wrap(
            Holder<NoiseGeneratorSettings> source,
            int yOffset,
            @Nullable Integer seaLevelOverride
    ) {
        NoiseGeneratorSettings settings = source.value();
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
        NoiseGeneratorSettings shifted = new NoiseGeneratorSettings(
                settings.noiseSettings(),
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
}
