package dimblend.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

public final class RotatingBiomeSource extends BiomeSource {
    public static final MapCodec<RotatingBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.listOf().fieldOf("sources").forGetter(source -> source.sources),
            Codec.INT.optionalFieldOf("band_size", BandIndex.DEFAULT_BAND_SIZE).forGetter(source -> source.bandSize)
    ).apply(instance, RotatingBiomeSource::new));

    /** BOP 的腐化群系只注册在 highlands 池里；走廊内退回 vanilla highlands。 */
    private static final ResourceKey<Biome> BOP_END_CORRUPTION = ResourceKey.create(
            Registries.BIOME, ResourceLocation.fromNamespaceAndPath("biomesoplenty", "end_corruption"));
    /** {@code |quartZ| <= 35} 覆盖 {@code |z| <= 128} 加上 BOP feature 的 7 格外溢。 */
    private static final int END_CORRUPTION_FILTER_QUART_Z = 35;

    private final List<BiomeSource> sources;
    private final int bandSize;
    @javax.annotation.Nullable
    private volatile Holder<Biome> barrens;
    private volatile boolean barrensResolved;
    @javax.annotation.Nullable
    private volatile Holder<Biome> endHighlands;
    private volatile boolean endHighlandsResolved;
    @javax.annotation.Nullable
    private RotatingChunkGenerator generator;

    public RotatingBiomeSource(List<BiomeSource> sources, int bandSize) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources empty");
        }
        if (bandSize < BandIndex.SEAM_WIDTH || bandSize % 16 != 0) {
            throw new IllegalArgumentException("bandSize");
        }
        this.sources = List.copyOf(sources);
        this.bandSize = bandSize;
    }

    void bind(RotatingChunkGenerator generator) {
        this.generator = generator;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return this.sources.stream().flatMap(source -> source.possibleBiomes().stream());
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int blockX = x << 2;
        int region = BandLayout.regionOfBlockX(blockX, this.bandSize);
        // Forced barren regions answer end_barrens for every query, not only for the palette
        // createBiomes stores. This source is asked outside chunk generation too (structure
        // placement state, locate, spawn-rule checks, the client's ungenerated positions); an End
        // highlands/midlands answer there would let chorus/end-city rules see a non-barren biome.
        if (BarrenRegionGeneration.isBarrenRegion(region)) {
            Holder<Biome> barren = this.barrenBiome();
            if (barren != null) {
                return barren;
            }
        }
        RotatingChunkGenerator rotating = this.generator;
        BandLayout layout = rotating == null ? null : rotating.layoutOrNull();
        if (layout == null) {
            int index = rotating == null ? 0 : BandLayout.fixedDelegateIndex(region, rotating.delegates());
            return filterEndCorruption(this.sources.get(index).getNoiseBiome(x, y, z, sampler), z);
        }
        if (layout.touchesSurfaceTwilightSeam(blockX, this.bandSize)) {
            float overworldWeight = layout.surfaceTwilightSeamWeight(blockX, this.bandSize);
            int index = overworldWeight >= 0.5f
                    ? layout.surfaceDelegateForSeam(blockX, this.bandSize)
                    : layout.twilightDelegateForSeam(blockX, this.bandSize);
            return filterEndCorruption(this.sources.get(index).getNoiseBiome(x, y, z, sampler), z);
        }
        int index = layout.delegateIndex(region);
        return filterEndCorruption(this.sources.get(index).getNoiseBiome(x, y, z, sampler), z);
    }

    /**
     * 末地走廊 {@code |z| <= 128}（{@code |quartZ| <= 35}）内不允许 BOP 腐化群系：
     * 它是 highlands 池成员，这里换成 vanilla {@code end_highlands}。找不到 highlands 时
     * 保留 delegate 答案，mock 或非末地布局不因本过滤而崩溃。
     */
    private Holder<Biome> filterEndCorruption(Holder<Biome> answer, int quartZ) {
        if (quartZ < -END_CORRUPTION_FILTER_QUART_Z || quartZ > END_CORRUPTION_FILTER_QUART_Z
                || !answer.is(BOP_END_CORRUPTION)) {
            return answer;
        }
        Holder<Biome> highlands = this.endHighlandsBiome();
        return highlands != null ? highlands : answer;
    }

    /** 同 {@link #barrenBiome()}：只扫一次，先写字段再写标志。 */
    @javax.annotation.Nullable
    private Holder<Biome> endHighlandsBiome() {
        if (!this.endHighlandsResolved) {
            Holder<Biome> found = null;
            for (Holder<Biome> candidate : this.possibleBiomes()) {
                if (candidate.is(Biomes.END_HIGHLANDS)) {
                    found = candidate;
                    break;
                }
            }
            this.endHighlands = found;
            this.endHighlandsResolved = true;
        }
        return this.endHighlands;
    }

    /**
     * {@code minecraft:end_barrens} taken from this source's own biome set — the union of the
     * delegates' sources, and {@code TheEndBiomeSource.collectPossibleBiomes} always includes it —
     * so no registry access is needed here. Null when nothing offers it (a mock or End-less test
     * layout), in which case the normal delegate answer stands: a forced region must not crash.
     *
     * <p>Resolved once, lock-free: a duplicate race just repeats the scan. {@code barrens} is
     * written before {@code barrensResolved}, so a reader that sees the flag sees the field.
     */
    @javax.annotation.Nullable
    private Holder<Biome> barrenBiome() {
        if (!this.barrensResolved) {
            Holder<Biome> found = null;
            for (Holder<Biome> candidate : this.possibleBiomes()) {
                if (candidate.is(Biomes.END_BARRENS)) {
                    found = candidate;
                    break;
                }
            }
            this.barrens = found;
            this.barrensResolved = true;
        }
        return this.barrens;
    }
}
