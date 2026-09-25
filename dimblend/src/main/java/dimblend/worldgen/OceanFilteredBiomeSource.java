package dimblend.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Wraps an inner biome source and rewrites every ocean biome (vanilla
 * {@code minecraft:is_ocean} tag, covering all deep/cold/lukewarm/warm variants, modded
 * oceans included when they carry the tag) plus mushroom fields to a fixed land biome.
 * Implements generation-rules.md 地下「阻止海洋生物群系及其变种生成」.
 *
 * <p>Filtering must happen at the source level, not as a chunk post-pass:
 * {@code NoiseBasedChunkGenerator} fills chunk biome sections and validates ocean
 * structures (monuments, shipwrecks, ...) through the same {@code biomeSource} field, so
 * both stay consistent here. {@code possibleBiomes()} is filtered as well, keeping
 * {@code /locate biome} and spawn-related consumers aligned. TerraBlender's mixins inject
 * modded region biomes inside the delegated vanilla source (they target
 * {@code MultiNoiseBiomeSource}/{@code Climate.ParameterList}), so query-time reads are
 * wrapper-transparent and only tagged oceans are rewritten. TerraBlender's init entry
 * ({@code LevelUtils.initializeBiomes}) however gates on the biome source type, so
 * TerraBlenderRotatingCompat must unwrap this filter before handing over the delegate.</p>
 *
 * <p>Wired up for the underground slice only (see
 * {@link SlicedOverworldChunkGenerator}); surface bands keep their oceans.</p>
 */
public final class OceanFilteredBiomeSource extends BiomeSource {
    public static final MapCodec<OceanFilteredBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("inner").forGetter(source -> source.inner)
    ).apply(instance, OceanFilteredBiomeSource::new));

    private final BiomeSource inner;
    private final Holder<Biome> replacement;

    public OceanFilteredBiomeSource(BiomeSource inner) {
        this.inner = inner;
        this.replacement = resolveReplacement(inner.possibleBiomes());
    }

    /**
     * The wrapped source. TerraBlender compat unwraps this before calling
     * {@code LevelUtils.initializeBiomes}, whose {@code instanceof MultiNoiseBiomeSource}
     * gate rejects wrapped sources and would silently skip the injection.
     */
    public BiomeSource inner() {
        return this.inner;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return this.inner.possibleBiomes().stream().filter(biome -> !isExcluded(biome));
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        Holder<Biome> biome = this.inner.getNoiseBiome(x, y, z, sampler);
        return isExcluded(biome) ? this.replacement : biome;
    }

    private static boolean isExcluded(Holder<Biome> biome) {
        return isExcludedBiome(biome);
    }

    /**
     * Shares the exclusion predicate with the underground terrain backfill in
     * {@link SlicedOverworldChunkGenerator}: a column whose unfiltered biome is excluded
     * here gets land-like rock instead of the ocean bowl, so biome label and terrain
     * stay consistent.
     */
    public static boolean isExcludedBiome(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(Biomes.MUSHROOM_FIELDS);
    }

    /**
     * Uniform plains replacement (north-star review decision; no climate-nearest
     * fallback). Falls back to any other land biome before failing hard on an inner
     * source that would be 100% excluded.
     */
    private static Holder<Biome> resolveReplacement(Set<Holder<Biome>> candidates) {
        for (Holder<Biome> candidate : candidates) {
            if (candidate.is(Biomes.PLAINS)) {
                return candidate;
            }
        }
        for (Holder<Biome> candidate : candidates) {
            if (!isExcluded(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("inner biome source has no replacement biome: " + candidates);
    }
}
