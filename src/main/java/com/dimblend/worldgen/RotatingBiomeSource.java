package com.dimblend.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

public final class RotatingBiomeSource extends BiomeSource {
    public static final MapCodec<RotatingBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.listOf().fieldOf("sources").forGetter(source -> source.sources),
            Codec.INT.optionalFieldOf("band_size", BandIndex.DEFAULT_BAND_SIZE).forGetter(source -> source.bandSize)
    ).apply(instance, RotatingBiomeSource::new));

    private final List<BiomeSource> sources;
    private final int bandSize;

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
        if (BandIndex.isOverworldTwilightSeam(blockX, this.bandSize, this.sources.size())) {
            float overworldWeight = BandIndex.overworldWeightAcrossTwilightSeam(blockX, this.bandSize, this.sources.size());
            int index = overworldWeight >= 0.5f
                    ? BandIndex.OVERWORLD_BAND
                    : BandIndex.twilightBand(this.sources.size());
            return this.sources.get(index).getNoiseBiome(x, y, z, sampler);
        }
        int index = BandIndex.ofQuartX(x, this.bandSize, this.sources.size());
        return this.sources.get(index).getNoiseBiome(x, y, z, sampler);
    }
}
