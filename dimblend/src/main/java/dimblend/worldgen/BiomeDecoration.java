package dimblend.worldgen;

import dimblend.mixin.ChunkGeneratorAccessor;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Vanilla {@code ChunkGenerator.applyBiomeDecoration} for {@code dimblend:rotating} only.
 * Two deltas: neighbor chunks in another band do not contribute biomes, and a feature
 * missing from the identity index is resolved by {@code equals} or skipped.
 */
public final class BiomeDecoration {
    private BiomeDecoration() {
    }

    public static void apply(ChunkGenerator generator, WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        ChunkPos chunkPos = chunk.getPos();
        if (SharedConstants.debugVoidTerrain(chunkPos)) {
            return;
        }
        SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSection());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<Integer, List<Structure>> structuresByStep = structureRegistry.stream()
                .collect(Collectors.groupingBy(structure -> structure.step().ordinal()));
        ChunkGeneratorAccessor access = (ChunkGeneratorAccessor) generator;
        List<FeatureSorter.StepFeatureData> steps = access.dimblend$featuresPerStep().get();
        Function<Holder<Biome>, BiomeGenerationSettings> generationSettings = access.dimblend$generationSettingsGetter();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        Set<Holder<Biome>> biomes = biomesInRange(level, generator, sectionPos);
        biomes.retainAll(generator.getBiomeSource().possibleBiomes());
        int stepCount = steps.size();

        try {
            Registry<PlacedFeature> placedFeatures = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
            int stepsToRun = Math.max(GenerationStep.Decoration.values().length, stepCount);
            for (int step = 0; step < stepsToRun; step++) {
                int structureIndex = 0;
                if (structures.shouldGenerateStructures()) {
                    for (Structure structure : structuresByStep.getOrDefault(step, Collections.emptyList())) {
                        random.setFeatureSeed(decorationSeed, structureIndex, step);
                        Supplier<String> structureName = () -> structureRegistry.getResourceKey(structure)
                                .map(Object::toString)
                                .orElseGet(structure::toString);
                        try {
                            level.setCurrentlyGenerating(structureName);
                            structures.startsForStructure(sectionPos, structure).forEach(start -> start.placeInChunk(
                                    level, structures, generator, random, writableArea(chunk), chunkPos));
                        } catch (Exception exception) {
                            CrashReport report = CrashReport.forThrowable(exception, "Feature placement");
                            report.addCategory("Feature").setDetail("Description", structureName::get);
                            throw new ReportedException(report);
                        }
                        structureIndex++;
                    }
                }
                if (step >= stepCount) {
                    continue;
                }
                FeatureSorter.StepFeatureData stepData = steps.get(step);
                IntSet featureIndices = new IntArraySet();
                for (Holder<Biome> biome : biomes) {
                    List<HolderSet<PlacedFeature>> biomeFeatures = generationSettings.apply(biome).features();
                    if (step >= biomeFeatures.size()) {
                        continue;
                    }
                    for (Holder<PlacedFeature> featureHolder : biomeFeatures.get(step)) {
                        int index = FeatureIndex.resolve(
                                stepData.features(), stepData.indexMapping(), featureHolder.value());
                        if (index >= 0) {
                            featureIndices.add(index);
                        }
                    }
                }
                int[] ordered = featureIndices.toIntArray();
                Arrays.sort(ordered);
                for (int featureIndex : ordered) {
                    PlacedFeature feature = stepData.features().get(featureIndex);
                    Supplier<String> featureName = () -> placedFeatures.getResourceKey(feature)
                            .map(Object::toString)
                            .orElseGet(feature::toString);
                    random.setFeatureSeed(decorationSeed, featureIndex, step);
                    try {
                        level.setCurrentlyGenerating(featureName);
                        feature.placeWithBiomeCheck(level, generator, random, origin);
                    } catch (Exception exception) {
                        CrashReport report = CrashReport.forThrowable(exception, "Feature placement");
                        report.addCategory("Feature").setDetail("Description", featureName::get);
                        throw new ReportedException(report);
                    }
                }
            }
            level.setCurrentlyGenerating(null);
        } catch (Exception exception) {
            CrashReport report = CrashReport.forThrowable(exception, "Biome decoration");
            report.addCategory("Generation")
                    .setDetail("CenterX", chunkPos.x)
                    .setDetail("CenterZ", chunkPos.z)
                    .setDetail("Decoration Seed", decorationSeed);
            throw new ReportedException(report);
        }
    }

    private static Set<Holder<Biome>> biomesInRange(WorldGenLevel level, ChunkGenerator generator, SectionPos sectionPos) {
        ServerLevel server = level.getLevel();
        RotatingChunkGenerator rotating = server.getChunkSource().getGenerator() instanceof RotatingChunkGenerator found
                ? found
                : null;
        int band = rotating == null
                ? -1
                : rotating.delegateIndexForBlockX(sectionPos.origin().getX());
        Set<Holder<Biome>> biomes = new ObjectArraySet<>();
        ChunkPos.rangeClosed(sectionPos.chunk(), 1).forEach(pos -> {
            ChunkAccess neighbor = level.getChunk(pos.x, pos.z);
            if (rotating != null && rotating.delegateIndexForBlockX(pos.getMinBlockX()) != band) {
                return;
            }
            for (LevelChunkSection section : neighbor.getSections()) {
                section.getBiomes().getAll(biomes::add);
            }
        });
        return biomes;
    }

    private static BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        LevelHeightAccessor height = chunk.getHeightAccessorForGeneration();
        int minY = height.getMinBuildHeight() + 1;
        int maxY = height.getMaxBuildHeight() - 1;
        return new BoundingBox(minX, minY, minZ, minX + 15, maxY, minZ + 15);
    }
}
