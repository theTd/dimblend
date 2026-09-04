package dimblend.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

public final class SlicedOverworldBiomeSource extends BiomeSource {
    public static final MapCodec<SlicedOverworldBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("inner").forGetter(source -> source.inner),
            OverworldSlice.CODEC.fieldOf("slice").forGetter(source -> source.slice)
    ).apply(instance, SlicedOverworldBiomeSource::new));

    private final BiomeSource inner;
    private final OverworldSlice slice;

    public SlicedOverworldBiomeSource(BiomeSource inner, OverworldSlice slice) {
        this.inner = inner;
        this.slice = slice;
    }

    public BiomeSource inner() {
        return this.inner;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return this.inner.possibleBiomes().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int sourceQuartY = y - QuartPos.fromBlock(this.slice.yOffset());
        return this.inner.getNoiseBiome(x, sourceQuartY, z, sampler);
    }
}
