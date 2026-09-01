package com.dimblend.worldgen;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class RotatingChunkGenerator extends ChunkGenerator {
    public static final MapCodec<RotatingChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ChunkGenerator.CODEC.listOf().fieldOf("delegates").forGetter(generator -> generator.delegates),
            Codec.INT.optionalFieldOf("band_size", BandIndex.DEFAULT_BAND_SIZE).forGetter(generator -> generator.bandSize)
    ).apply(instance, RotatingChunkGenerator::new));

    private static final int MIN_Y = -64;
    private static final int GEN_DEPTH = 384;
    private static final int SEA_LEVEL = 63;

    private final List<ChunkGenerator> delegates;
    private final int bandSize;
    @Nullable
    private volatile RuntimeState runtime;

    private record RuntimeState(long seed, RandomState[] randoms, ChunkGeneratorStructureState[] states) {
        private RandomState random(int index) {
            return this.randoms[index];
        }

        private ChunkGeneratorStructureState state(int index) {
            return this.states[index];
        }
    }

    public RotatingChunkGenerator(List<ChunkGenerator> delegates, int bandSize) {
        super(new RotatingBiomeSource(
                delegates.stream().map(ChunkGenerator::getBiomeSource).toList(),
                bandSize
        ));
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("delegates empty");
        }
        if (bandSize < BandIndex.SEAM_WIDTH || bandSize % 16 != 0) {
            throw new IllegalArgumentException("bandSize");
        }
        this.delegates = List.copyOf(delegates);
        this.bandSize = bandSize;
    }

    public List<ChunkGenerator> delegates() {
        return this.delegates;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private ChunkGenerator delegate(ChunkPos pos) {
        return this.delegates.get(BandIndex.ofChunk(pos, this.bandSize, this.delegates.size()));
    }

    private int bandIndex(ChunkPos pos) {
        return BandIndex.ofChunk(pos, this.bandSize, this.delegates.size());
    }

    private int bandIndexBlock(int blockX) {
        return BandIndex.ofBlockX(blockX, this.bandSize, this.delegates.size());
    }

    private synchronized void ensureRuntime(RegistryAccess access) {
        RuntimeState current = this.runtime;
        if (current == null) {
            throw new IllegalStateException("dimblend world seed is not set");
        }
        if (current.randoms != null) {
            return;
        }
        this.runtime = this.buildRuntime(access, current.seed);
    }

    private RuntimeState buildRuntime(RegistryAccess access, long seed) {
        int count = this.delegates.size();
        RandomState[] randoms = new RandomState[count];
        ChunkGeneratorStructureState[] states = new ChunkGeneratorStructureState[count];
        HolderLookup<StructureSet> structureSets = access.lookupOrThrow(Registries.STRUCTURE_SET);
        for (int i = 0; i < count; i++) {
            ChunkGenerator delegate = this.delegates.get(i);
            ChunkGenerator noiseSource = delegate instanceof SlicedOverworldChunkGenerator sliced
                    ? sliced.inner()
                    : delegate;
            if (noiseSource instanceof NoiseBasedChunkGenerator noise) {
                randoms[i] = RandomState.create(
                        noise.generatorSettings().value(),
                        access.lookupOrThrow(Registries.NOISE),
                        seed
                );
            } else {
                randoms[i] = RandomState.create(
                        NoiseGeneratorSettings.dummy(),
                        access.lookupOrThrow(Registries.NOISE),
                        seed
                );
            }
            states[i] = delegate.createState(structureSets, randoms[i], seed);
        }
        return new RuntimeState(seed, randoms, states);
    }

    private void ensureRuntimeFromLevel(WorldGenLevel level) {
        this.ensureRuntime(level.registryAccess());
    }

    private RuntimeState requireRuntime() {
        RuntimeState current = this.runtime;
        if (current == null || current.randoms == null) {
            throw new IllegalStateException("delegate randoms not initialized");
        }
        return current;
    }

    private RandomState delegateRandom(int index) {
        return this.requireRuntime().random(index);
    }

    private ChunkGeneratorStructureState delegateState(int index) {
        return this.requireRuntime().state(index);
    }

    @Override
    public synchronized ChunkGeneratorStructureState createState(
            HolderLookup<StructureSet> lookup,
            RandomState randomState,
            long seed
    ) {
        RuntimeState current = this.runtime;
        if (current == null || current.seed != seed || current.randoms == null) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                this.runtime = this.buildRuntime(server.registryAccess(), seed);
            } else {
                this.runtime = new RuntimeState(seed, null, null);
            }
        }
        return ChunkGeneratorStructureState.createForNormal(randomState, seed, this.getBiomeSource(), lookup);
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public int getGenDepth() {
        return GEN_DEPTH;
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState,
            Blender blender,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        this.ensureRuntime(structureManager.registryAccess());
        int index = this.bandIndex(chunk.getPos());
        return this.delegates.get(index).createBiomes(this.delegateRandom(index), blender, structureManager, chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        this.ensureRuntime(structureManager.registryAccess());
        int index = this.bandIndex(chunk.getPos());
        CompletableFuture<ChunkAccess> nativeFill = this.delegates.get(index).fillFromNoise(
                blender,
                this.delegateRandom(index),
                structureManager,
                chunk
        );
        if (!BandIndex.chunkTouchesOverworldTwilightSeam(chunk.getPos(), this.bandSize, this.delegates.size())) {
            return nativeFill;
        }
        return nativeFill.thenApply(this::adjustOverworldTwilightSeamHeights);
    }

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess chunk
    ) {
        this.ensureRuntimeFromLevel(level);
        int index = this.bandIndex(chunk.getPos());
        this.delegates.get(index).buildSurface(level, structureManager, this.delegateRandom(index), chunk);
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
        this.ensureRuntimeFromLevel(level);
        int index = this.bandIndex(chunk.getPos());
        this.delegates.get(index).applyCarvers(
                level,
                seed,
                this.delegateRandom(index),
                biomeManager,
                structureManager,
                chunk,
                step
        );
    }

    @Override
    public int getBaseHeight(int x, int z, Types type, LevelHeightAccessor height, RandomState randomState) {
        this.ensureRuntimeOrThrow();
        if (BandIndex.isOverworldTwilightSeam(x, this.bandSize, this.delegates.size())) {
            return this.blendedSurfaceHeight(x, z, height, type) + 1;
        }
        int index = this.bandIndexBlock(x);
        return this.delegates.get(index).getBaseHeight(x, z, type, height, this.delegateRandom(index));
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState randomState) {
        this.ensureRuntimeOrThrow();
        int index = this.bandIndexBlock(x);
        return this.delegates.get(index).getBaseColumn(x, z, height, this.delegateRandom(index));
    }

    private ChunkAccess adjustOverworldTwilightSeamHeights(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = minY + chunk.getHeight() - 1;
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Types.WORLD_SURFACE_WG);
        MutableBlockPos cursor = new MutableBlockPos();
        int[][] overworldGrid = this.sampleHeightGrid(
                this.delegates.get(BandIndex.OVERWORLD_BAND), BandIndex.OVERWORLD_BAND, minX, minZ, chunk);
        int[][] twilightGrid = this.sampleHeightGrid(
                this.delegates.get(BandIndex.TWILIGHT_BAND), BandIndex.TWILIGHT_BAND, minX, minZ, chunk);
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            chunk.getSection(sectionIndex).acquire();
        }
        try {
            for (int lx = 0; lx < 16; lx++) {
                int x = minX + lx;
                if (!BandIndex.isOverworldTwilightSeam(x, this.bandSize, this.delegates.size())) {
                    continue;
                }
                float overworldWeight = BandIndex.overworldWeightAcrossTwilightSeam(x, this.bandSize, this.delegates.size());
                for (int lz = 0; lz < 16; lz++) {
                    int z = minZ + lz;
                    int sourceTop = Math.max(minY, ocean.getFirstAvailable(lx, lz) - 1);
                    int overworldTop = interpolateGrid(overworldGrid, lx, lz);
                    int twilightTop = interpolateGrid(twilightGrid, lx, lz);
                    int blendedTop = Math.clamp(
                            (int) Math.round(twilightTop + (overworldTop - twilightTop) * overworldWeight),
                            minY,
                            maxY
                    );
                    if (blendedTop == sourceTop) {
                        continue;
                    }
                    BlockState fill = this.subsurfaceFill(chunk, lx, lz, sourceTop, minY);
                    BlockState air = Blocks.AIR.defaultBlockState();
                    int oldSurface = Math.max(minY, surface.getFirstAvailable(lx, lz) - 1);
                    BlockState nativeFluid = this.findNativeFluid(chunk, lx, lz, sourceTop + 1, oldSurface);
                    if (blendedTop > sourceTop) {
                        for (int y = sourceTop + 1; y <= blendedTop; y++) {
                            this.writeSeamBlock(chunk, ocean, surface, cursor, x, y, z, lx, lz, fill);
                        }
                    } else {
                        for (int y = blendedTop + 1; y <= sourceTop; y++) {
                            this.writeSeamBlock(chunk, ocean, surface, cursor, x, y, z, lx, lz, air);
                        }
                    }
                    if (nativeFluid != null && oldSurface > blendedTop) {
                        for (int y = blendedTop + 1; y <= oldSurface; y++) {
                            this.writeSeamBlock(chunk, ocean, surface, cursor, x, y, z, lx, lz, nativeFluid);
                        }
                    }
                }
            }
        } finally {
            for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
                chunk.getSection(sectionIndex).release();
            }
        }
        return chunk;
    }

    private int[][] sampleHeightGrid(ChunkGenerator generator, int band, int minX, int minZ, LevelHeightAccessor height) {
        int[][] grid = new int[5][5];
        RandomState random = this.delegateRandom(band);
        for (int gx = 0; gx < 5; gx++) {
            int x = minX + gx * 4;
            for (int gz = 0; gz < 5; gz++) {
                int z = minZ + gz * 4;
                grid[gx][gz] = generator.getBaseHeight(x, z, Types.OCEAN_FLOOR_WG, height, random) - 1;
            }
        }
        return grid;
    }

    private static int interpolateGrid(int[][] grid, int lx, int lz) {
        int gx = Math.min(3, lx / 4);
        int gz = Math.min(3, lz / 4);
        float tx = (lx - gx * 4) / 4.0f;
        float tz = (lz - gz * 4) / 4.0f;
        float v00 = grid[gx][gz];
        float v10 = grid[gx + 1][gz];
        float v01 = grid[gx][gz + 1];
        float v11 = grid[gx + 1][gz + 1];
        float v0 = v00 + (v10 - v00) * tx;
        float v1 = v01 + (v11 - v01) * tx;
        return Math.round(v0 + (v1 - v0) * tz);
    }

    private void writeSeamBlock(
            ChunkAccess chunk,
            Heightmap ocean,
            Heightmap surface,
            MutableBlockPos cursor,
            int x,
            int y,
            int z,
            int lx,
            int lz,
            BlockState state
    ) {
        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(lx, y & 15, lz, state, false);
        ocean.update(lx, y, lz, state);
        surface.update(lx, y, lz, state);
        if (!state.getFluidState().isEmpty()) {
            cursor.set(x, y, z);
            chunk.markPosForPostprocessing(cursor);
        }
    }

    private BlockState subsurfaceFill(ChunkAccess chunk, int lx, int lz, int sourceTop, int minY) {
        for (int y = sourceTop - 1; y >= minY; y--) {
            BlockState state = chunk.getSection(chunk.getSectionIndex(y)).getBlockState(lx, y & 15, lz);
            if (isSolidTerrain(state)) {
                return state;
            }
        }
        return Blocks.STONE.defaultBlockState();
    }

    private BlockState findNativeFluid(ChunkAccess chunk, int lx, int lz, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            BlockState state = chunk.getSection(chunk.getSectionIndex(y)).getBlockState(lx, y & 15, lz);
            if (state != null && !state.getFluidState().isEmpty()) {
                return state;
            }
        }
        return null;
    }


    private int blendedSurfaceHeight(int x, int z, LevelHeightAccessor height, Types type) {
        int overworldTop = this.delegates.get(BandIndex.OVERWORLD_BAND).getBaseHeight(
                x, z, type, height, this.delegateRandom(BandIndex.OVERWORLD_BAND)
        ) - 1;
        int twilightTop = this.delegates.get(BandIndex.TWILIGHT_BAND).getBaseHeight(
                x, z, type, height, this.delegateRandom(BandIndex.TWILIGHT_BAND)
        ) - 1;
        float overworldWeight = BandIndex.overworldWeightAcrossTwilightSeam(x, this.bandSize, this.delegates.size());
        return Math.round(twilightTop + (overworldTop - twilightTop) * overworldWeight);
    }

    private static boolean isSolidTerrain(BlockState state) {
        return state != null && !state.isAir() && state.getFluidState().isEmpty() && state.blocksMotion();
    }

    private void ensureRuntimeOrThrow() {
        RuntimeState current = this.runtime;
        if (current != null && current.randoms != null) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("dimblend delegate RandomState requires a running server");
        }
        this.ensureRuntime(server.registryAccess());
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        this.ensureRuntimeFromLevel(level);
        this.delegate(level.getCenter()).spawnOriginalMobs(level);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        int index = this.bandIndexBlock(pos.getX());
        info.add("dimblend band=" + index + " size=" + this.bandSize);
        this.ensureRuntimeOrThrow();
        this.delegates.get(index).addDebugScreenInfo(info, this.delegateRandom(index), pos);
    }

    @Override
    public void createStructures(
            RegistryAccess access,
            ChunkGeneratorStructureState ignored,
            StructureManager structures,
            ChunkAccess chunk,
            StructureTemplateManager templates
    ) {
        this.ensureRuntime(access);
        int index = this.bandIndex(chunk.getPos());
        this.delegates.get(index).createStructures(access, this.delegateState(index), structures, chunk, templates);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        this.ensureRuntimeFromLevel(level);
        int index = this.bandIndex(chunk.getPos());
        this.delegates.get(index).applyBiomeDecoration(level, chunk, structures);
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        this.ensureRuntimeFromLevel(level);
        int index = this.bandIndex(chunk.getPos());
        this.delegates.get(index).createReferences(level, structures, chunk);
    }

    @Override
    @Nullable
    public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(
            ServerLevel level,
            HolderSet<Structure> target,
            BlockPos pos,
            int radius,
            boolean skipKnown
    ) {
        this.ensureRuntime(level.registryAccess());
        Pair<BlockPos, Holder<Structure>> nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < this.delegates.size(); i++) {
            ChunkGenerator delegate = this.delegates.get(i);
            if (delegate instanceof SlicedOverworldChunkGenerator sliced
                    && sliced.slice() == OverworldSlice.UNDERGROUND) {
                continue;
            }
            Pair<BlockPos, Holder<Structure>> candidate = this.findNearestForDelegate(
                    i,
                    this.delegateState(i),
                    level,
                    target,
                    pos,
                    radius,
                    skipKnown
            );
            if (candidate != null) {
                double distance = pos.distSqr(candidate.getFirst());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = candidate;
                }
            }
        }
        return nearest;
    }

    @Nullable
    private Pair<BlockPos, Holder<Structure>> findNearestForDelegate(
            int bandIndex,
            ChunkGeneratorStructureState state,
            ServerLevel level,
            HolderSet<Structure> target,
            BlockPos pos,
            int radius,
            boolean skipKnown
    ) {
        Map<StructurePlacement, Set<Holder<Structure>>> placements = new Object2ObjectArrayMap<>();
        for (Holder<Structure> structure : target) {
            for (StructurePlacement placement : state.getPlacementsForStructure(structure)) {
                placements.computeIfAbsent(placement, unused -> new ObjectArraySet<>()).add(structure);
            }
        }
        if (placements.isEmpty()) {
            return null;
        }

        Pair<BlockPos, Holder<Structure>> nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        StructureManager structures = level.structureManager();
        List<Entry<StructurePlacement, Set<Holder<Structure>>>> randomSpread = new ArrayList<>(placements.size());

        for (Entry<StructurePlacement, Set<Holder<Structure>>> entry : placements.entrySet()) {
            StructurePlacement placement = entry.getKey();
            if (placement instanceof ConcentricRingsStructurePlacement rings) {
                Pair<BlockPos, Holder<Structure>> candidate = this.getNearestGeneratedStructure(
                        bandIndex,
                        entry.getValue(),
                        level,
                        structures,
                        pos,
                        skipKnown,
                        rings,
                        state
                );
                if (candidate != null) {
                    double distance = pos.distSqr(candidate.getFirst());
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = candidate;
                    }
                }
            } else if (placement instanceof RandomSpreadStructurePlacement) {
                randomSpread.add(entry);
            }
        }

        if (!randomSpread.isEmpty()) {
            int sectionX = SectionPos.blockToSectionCoord(pos.getX());
            int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
            for (int searchRadius = 0; searchRadius <= radius; searchRadius++) {
                boolean found = false;
                for (Entry<StructurePlacement, Set<Holder<Structure>>> entry : randomSpread) {
                    RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) entry.getKey();
                    Pair<BlockPos, Holder<Structure>> candidate = getNearestGeneratedStructure(
                            bandIndex,
                            this.bandSize,
                            this.delegates.size(),
                            entry.getValue(),
                            level,
                            structures,
                            sectionX,
                            sectionZ,
                            searchRadius,
                            skipKnown,
                            state.getLevelSeed(),
                            placement
                    );
                    if (candidate != null) {
                        found = true;
                        double distance = pos.distSqr((Vec3i) candidate.getFirst());
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearest = candidate;
                        }
                    }
                }
                if (found) {
                    return nearest;
                }
            }
        }
        return nearest;
    }

    @Nullable
    private Pair<BlockPos, Holder<Structure>> getNearestGeneratedStructure(
            int bandIndex,
            Set<Holder<Structure>> structures,
            ServerLevel level,
            StructureManager manager,
            BlockPos pos,
            boolean skipKnown,
            ConcentricRingsStructurePlacement placement,
            ChunkGeneratorStructureState state
    ) {
        List<ChunkPos> rings = state.getRingPositionsFor(placement);
        if (rings == null) {
            throw new IllegalStateException("Somehow tried to find structures for a placement that doesn't exist");
        }
        Pair<BlockPos, Holder<Structure>> nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        MutableBlockPos scratch = new MutableBlockPos();
        for (ChunkPos chunkPos : rings) {
            if (BandIndex.ofChunk(chunkPos, this.bandSize, this.delegates.size()) != bandIndex) {
                continue;
            }
            scratch.set(SectionPos.sectionToBlockCoord(chunkPos.x, 8), 32, SectionPos.sectionToBlockCoord(chunkPos.z, 8));
            double distance = scratch.distSqr(pos);
            if (nearest == null || distance < nearestDistance) {
                Pair<BlockPos, Holder<Structure>> candidate = getStructureGeneratingAt(
                        structures,
                        level,
                        manager,
                        skipKnown,
                        placement,
                        chunkPos
                );
                if (candidate != null) {
                    nearest = candidate;
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    @Nullable
    private static Pair<BlockPos, Holder<Structure>> getNearestGeneratedStructure(
            int bandIndex,
            int bandSize,
            int bandCount,
            Set<Holder<Structure>> structures,
            LevelReader level,
            StructureManager manager,
            int sectionX,
            int sectionZ,
            int searchRadius,
            boolean skipKnown,
            long seed,
            RandomSpreadStructurePlacement placement
    ) {
        int spacing = placement.spacing();
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            boolean edgeX = dx == -searchRadius || dx == searchRadius;
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                boolean edgeZ = dz == -searchRadius || dz == searchRadius;
                if (edgeX || edgeZ) {
                    int x = sectionX + spacing * dx;
                    int z = sectionZ + spacing * dz;
                    ChunkPos chunkPos = placement.getPotentialStructureChunk(seed, x, z);
                    if (BandIndex.ofChunk(chunkPos, bandSize, bandCount) != bandIndex) {
                        continue;
                    }
                    Pair<BlockPos, Holder<Structure>> candidate = getStructureGeneratingAt(
                            structures,
                            level,
                            manager,
                            skipKnown,
                            placement,
                            chunkPos
                    );
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static Pair<BlockPos, Holder<Structure>> getStructureGeneratingAt(
            Set<Holder<Structure>> structures,
            LevelReader level,
            StructureManager manager,
            boolean skipKnown,
            StructurePlacement placement,
            ChunkPos chunkPos
    ) {
        for (Holder<Structure> holder : structures) {
            StructureCheckResult result = manager.checkStructurePresence(chunkPos, holder.value(), placement, skipKnown);
            if (result != StructureCheckResult.START_NOT_PRESENT) {
                if (!skipKnown && result == StructureCheckResult.START_PRESENT) {
                    return Pair.of(placement.getLocatePos(chunkPos), holder);
                }
                ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS);
                StructureStart start = manager.getStartForStructure(SectionPos.bottomOf(chunk), holder.value(), chunk);
                if (start != null && start.isValid() && (!skipKnown || tryAddReference(manager, start))) {
                    return Pair.of(placement.getLocatePos(start.getChunkPos()), holder);
                }
            }
        }
        return null;
    }

    private static boolean tryAddReference(StructureManager manager, StructureStart start) {
        if (start.canBeReferenced()) {
            manager.addReference(start);
            return true;
        }
        return false;
    }
}
