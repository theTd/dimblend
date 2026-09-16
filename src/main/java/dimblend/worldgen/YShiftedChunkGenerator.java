package dimblend.worldgen;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dimblend.mixin.ChunkGeneratorAccessor;
import dimblend.mixin.NoiseBasedChunkGeneratorAccessor;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Keeps a mod's own {@link ChunkGenerator} subclass (Voidscape's {@code voidscape:void})
 * and shifts its terrain the same way {@link YShiftedNoiseChunkGenerator} shifts Twilight:
 * density sampled at {@code y - offset}, biome columns translated by the same amount.
 * Reconstructing a vanilla {@link NoiseBasedChunkGenerator} would drop Voidscape's
 * half-cell noise and 3D biome decoration, so the inner instance is mutated in place
 * after codec construction (bean injection stays intact).
 *
 * <p>{@code y_offset: -64} drops Voidscape islands so the Y=64 corridor sits 64 blocks
 * higher relative to the original island surface.
 */
public final class YShiftedChunkGenerator extends ChunkGenerator {
    /**
     * Voidscape in {@code dimblend:rotating}: original 0–256 islands move to -64–192.
     */
    public static final int VOIDSCAPE_Y_OFFSET = -64;

    public static final MapCodec<YShiftedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ChunkGenerator.CODEC.fieldOf("inner").forGetter(generator -> generator.inner),
            Codec.INT.fieldOf("y_offset").forGetter(generator -> generator.yOffset)
    ).apply(instance, YShiftedChunkGenerator::parse));

    private final ChunkGenerator inner;
    private final int yOffset;
    @Nullable
    private final ResourceLocation sourceIdentity;

    public static YShiftedChunkGenerator parse(ChunkGenerator inner, int yOffset) {
        ResourceLocation identity = sourceIdentityOf(inner);
        applyShift(inner, yOffset);
        return new YShiftedChunkGenerator(inner, yOffset, identity);
    }

    private YShiftedChunkGenerator(ChunkGenerator inner, int yOffset, @Nullable ResourceLocation sourceIdentity) {
        super(inner.getBiomeSource());
        this.inner = inner;
        this.yOffset = yOffset;
        this.sourceIdentity = sourceIdentity;
    }

    public ChunkGenerator inner() {
        return this.inner;
    }

    public int yOffset() {
        return this.yOffset;
    }

    /**
     * Pre-shift settings key or generator type, e.g. {@code voidscape:void}.
     * After {@link #applyShift} the inner settings holder is direct and has no key,
     * matching {@link YShiftedNoiseChunkGenerator#sourceSettingsKey()}.
     */
    @Nullable
    public ResourceLocation sourceIdentity() {
        return this.sourceIdentity;
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
        YShiftScope.run(this.yOffset, () -> this.inner.createStructures(access, state, structures, chunk, templates));
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        YShiftScope.run(this.yOffset, () -> this.inner.createReferences(level, structures, chunk));
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState,
            Blender blender,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        return this.inner.createBiomes(randomState, blender, structureManager, chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        return this.inner.fillFromNoise(blender, randomState, structureManager, chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        YShiftScope.run(this.yOffset, () -> this.inner.buildSurface(level, structureManager, randomState, chunk));
    }

    @Override
    public void applyCarvers(
            WorldGenRegion level,
            long seed,
            RandomState randomState,
            BiomeManager biomeManager,
            StructureManager structureManager,
            ChunkAccess chunk,
            Carving step
    ) {
        YShiftScope.run(this.yOffset, () -> this.inner.applyCarvers(
                level, seed, randomState, biomeManager, structureManager, chunk, step));
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        YShiftScope.run(this.yOffset, () -> this.inner.applyBiomeDecoration(level, chunk, structures));
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        YShiftScope.run(this.yOffset, () -> this.inner.spawnOriginalMobs(level));
    }

    @Override
    public int getMinY() {
        return this.inner.getMinY();
    }

    @Override
    public int getGenDepth() {
        return this.inner.getGenDepth();
    }

    @Override
    public int getSeaLevel() {
        return this.inner.getSeaLevel();
    }

    @Override
    public int getBaseHeight(int x, int z, Types type, LevelHeightAccessor height, RandomState randomState) {
        return this.inner.getBaseHeight(x, z, type, height, randomState);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState randomState) {
        return this.inner.getBaseColumn(x, z, height, randomState);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("dimblend y_shift offset=" + this.yOffset);
        this.inner.addDebugScreenInfo(info, randomState, pos.offset(0, -this.yOffset, 0));
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> lookup, RandomState randomState, long seed) {
        return YShiftScope.get(this.yOffset, () -> this.inner.createState(lookup, randomState, seed));
    }

    @Nullable
    static ResourceLocation sourceIdentityOf(ChunkGenerator inner) {
        if (inner instanceof NoiseBasedChunkGenerator noise) {
            ResourceLocation key = noise.generatorSettings().unwrapKey().map(ResourceKey::location).orElse(null);
            if (key != null) {
                return key;
            }
        }
        return inner.getTypeNameForDataFixer().map(ResourceKey::location).orElse(null);
    }

    /**
     * Mutates {@code inner} so its density / biome columns live at {@code yOffset}.
     * Idempotent: a second call with the same offset is a no-op (world save round-trip).
     */
    static void applyShift(ChunkGenerator inner, int yOffset) {
        if (yOffset == 0) {
            return;
        }
        if (!(inner instanceof NoiseBasedChunkGenerator noise)) {
            throw new IllegalStateException(
                    "dimblend:y_shifted inner must be noise-based, got " + inner.getClass().getName());
        }
        NoiseGeneratorSettings settings = noise.generatorSettings().value();
        if (YShiftedNoiseSettings.alreadyShifted(settings, yOffset)) {
            return;
        }
        if (settings.noiseRouter().finalDensity() instanceof YShiftedDensity shifted) {
            throw new IllegalStateException(
                    "dimblend:y_shifted inner is already shifted by " + shifted.offset()
                            + ", cannot apply " + yOffset);
        }
        BiomeSource biomes = YShiftedBiomeSource.wrap(inner.getBiomeSource(), yOffset);
        ((ChunkGeneratorAccessor) inner).dimblend$setBiomeSource(biomes);
        inner.refreshFeaturesPerStep();
        var shifted = YShiftedNoiseSettings.wrap(noise.generatorSettings(), yOffset, null);
        ((NoiseBasedChunkGeneratorAccessor) noise).dimblend$setSettings(shifted);
        ((NoiseBasedChunkGeneratorAccessor) noise).dimblend$setGlobalFluidPicker(
                Suppliers.memoize(() -> NoiseBasedChunkGeneratorAccessor.dimblend$createFluidPicker(shifted.value())));
    }
}
