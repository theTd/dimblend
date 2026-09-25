package dimblend.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Quart-Y translation matching {@link YShiftedDensity}: TF's biome columns pick
 * surface vs underground by comparing the quart Y to keys near 0 ({@code -1}/{@code -3}),
 * so sampling the inner source at {@code y - quart(offset)} keeps those layers glued
 * to the lifted terrain instead of the original sea-level window.
 */
public final class YShiftedBiomeSource extends BiomeSource {
    public static final MapCodec<YShiftedBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("inner").forGetter(source -> source.inner),
            Codec.INT.fieldOf("y_offset").forGetter(source -> source.yOffset)
    ).apply(instance, YShiftedBiomeSource::new));

    private final BiomeSource inner;
    private final int yOffset;
    private final int quartOffset;

    public static BiomeSource wrap(BiomeSource inner, int yOffset) {
        if (yOffset == 0 || inner instanceof YShiftedBiomeSource) {
            return inner;
        }
        return new YShiftedBiomeSource(inner, yOffset);
    }

    public YShiftedBiomeSource(BiomeSource inner, int yOffset) {
        this.inner = inner;
        this.yOffset = yOffset;
        this.quartOffset = QuartPos.fromBlock(yOffset);
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
        return this.inner.getNoiseBiome(x, y - this.quartOffset, z, sampler);
    }
}
