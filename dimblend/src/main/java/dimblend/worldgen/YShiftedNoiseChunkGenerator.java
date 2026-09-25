package dimblend.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Vanilla noise generator that wraps another mod's {@link NoiseGeneratorSettings} in code.
 * The wrapped settings' final and initial density are evaluated through {@link YShiftedDensity},
 * so the source dimension's terrain renders shifted up by {@code y_offset} blocks. The biome
 * source is wrapped in {@link YShiftedBiomeSource} with the same offset so TF's surface /
 * underground column keys stay glued to the lifted ground. The surface rule, default
 * block/fluid, noise envelope and every other router component are reused from the source
 * holder, so upstream data changes apply automatically and nothing is forked.
 *
 * <p>The wrapped value is held as a direct holder: it exists only at runtime, so codec
 * serialization records the source settings key plus the offsets and the wrapper is rebuilt
 * on decode.
 */
public final class YShiftedNoiseChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<YShiftedNoiseChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.sourceBiomes),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.sourceSettings),
            Codec.INT.fieldOf("y_offset").forGetter(generator -> generator.yOffset),
            Codec.INT.optionalFieldOf("sea_level").forGetter(generator -> Optional.ofNullable(generator.seaLevelOverride))
    ).apply(instance, YShiftedNoiseChunkGenerator::new));

    private final BiomeSource sourceBiomes;
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
        super(YShiftedBiomeSource.wrap(biomeSource, yOffset), YShiftedNoiseSettings.wrap(sourceSettings, yOffset, seaLevelOverride));
        this.sourceBiomes = biomeSource;
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

    @Override
    public void createStructures(
            RegistryAccess access,
            ChunkGeneratorStructureState state,
            StructureManager structures,
            ChunkAccess chunk,
            StructureTemplateManager templates
    ) {
        YShiftScope.run(this.yOffset, () -> super.createStructures(access, state, structures, chunk, templates));
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        YShiftScope.run(this.yOffset, () -> super.applyBiomeDecoration(level, chunk, structures));
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) {
        YShiftScope.run(this.yOffset, () -> super.buildSurface(level, structures, random, chunk));
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState random,
            StructureManager structures,
            ChunkAccess chunk
    ) {
        int noiseMinY = this.generatorSettings().value().noiseSettings().minY();
        BlockState fill = this.generatorSettings().value().defaultBlock();
        return super.fillFromNoise(blender, random, structures, chunk)
                .thenApply(filled -> fillBelowNoise(filled, noiseMinY, fill));
    }

    private static ChunkAccess fillBelowNoise(ChunkAccess chunk, int noiseMinY, BlockState fill) {
        int minY = chunk.getMinBuildHeight();
        if (minY >= noiseMinY) {
            return chunk;
        }
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        int sections = chunk.getSectionsCount();
        for (int sectionIndex = 0; sectionIndex < sections; sectionIndex++) {
            chunk.getSection(sectionIndex).acquire();
        }
        try {
            // PalettedContainer's ThreadingDetector is a non-reentrant Semaphore(1).
            // ProtoChunk.setBlockState → section.setBlockState(..., true) → getAndSet → acquire()
            // would self-deadlock on the permit this loop already holds. Write with useLocks=false
            // (getAndSetUnchecked), matching vanilla NoiseBasedChunkGenerator.fillFromNoise.
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = minY; y < noiseMinY; y++) {
                        BlockState state = y <= minY + 4 ? bedrock : fill;
                        chunk.getSection(chunk.getSectionIndex(y))
                                .setBlockState(lx, y & 15, lz, state, false);
                    }
                }
            }
        } finally {
            for (int sectionIndex = 0; sectionIndex < sections; sectionIndex++) {
                chunk.getSection(sectionIndex).release();
            }
        }
        return chunk;
    }

    @Override
    public void applyCarvers(
            WorldGenRegion level,
            long seed,
            RandomState random,
            BiomeManager biomes,
            StructureManager structures,
            ChunkAccess chunk,
            GenerationStep.Carving step
    ) {
        YShiftScope.run(this.yOffset, () -> super.applyCarvers(level, seed, random, biomes, structures, chunk, step));
    }
}
