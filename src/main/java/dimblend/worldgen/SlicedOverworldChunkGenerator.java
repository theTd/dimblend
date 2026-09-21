package dimblend.worldgen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.ChunkAccess;import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;

public final class SlicedOverworldChunkGenerator extends ChunkGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final MapCodec<SlicedOverworldChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ChunkGenerator.CODEC.fieldOf("inner").forGetter(generator -> generator.inner),
            OverworldSlice.CODEC.fieldOf("slice").forGetter(generator -> generator.slice)
    ).apply(instance, SlicedOverworldChunkGenerator::parse));

    private final ChunkGenerator inner;
    private final OverworldSlice slice;

    /**
     * JSON entry point. Applies the underground ocean filter exactly once: rebuilding the
     * delegate a second time (codec round-trip of an already filtered generator) hits the
     * idempotence guard in {@link #applyUndergroundOceanFilter}.
     */
    public static SlicedOverworldChunkGenerator parse(ChunkGenerator inner, OverworldSlice slice) {
        return new SlicedOverworldChunkGenerator(applyUndergroundOceanFilter(inner, slice), slice);
    }

    private SlicedOverworldChunkGenerator(ChunkGenerator inner, OverworldSlice slice) {
        super(slice.yOffset() == 0 ? inner.getBiomeSource() : new SlicedOverworldBiomeSource(inner.getBiomeSource(), slice));
        this.inner = inner;
        this.slice = slice;
    }

    /**
     * The underground slice draws biomes from the full overworld climate, oceans included.
     * generation-rules.md 地下 requires those to be replaced, and the swap has to happen
     * on the delegate's own biome source: NoiseBasedChunkGenerator reads that field for
     * both chunk biome filling and ocean-structure placement checks, so filtering any
     * outer wrapper alone would leak oceans into stored chunks and structures. Only the
     * exact vanilla {@code minecraft:noise} shape is rebuilt; subclasses (e.g.
     * {@code dimblend:y_shifted_noise}) carry state this plain rebuild would drop, so they
     * are skipped with a warning instead of being silently downcast.
     */
    private static ChunkGenerator applyUndergroundOceanFilter(ChunkGenerator inner, OverworldSlice slice) {
        if (slice != OverworldSlice.UNDERGROUND) {
            return inner;
        }
        if (!(inner instanceof NoiseBasedChunkGenerator noise)) {
            LOGGER.warn("underground slice inner generator {} is not noise-based; ocean filter not installed", inner.getClass().getName());
            return inner;
        }
        if (noise.getBiomeSource() instanceof OceanFilteredBiomeSource) {
            return inner;
        }
        if (noise.getClass() != NoiseBasedChunkGenerator.class) {
            LOGGER.warn(
                    "underground slice inner generator {} is a NoiseBasedChunkGenerator subclass; ocean filter and terrain backfill skipped so its subclass state is not lost",
                    noise.getClass().getName()
            );
            return inner;
        }
        return new NoiseBasedChunkGenerator(new OceanFilteredBiomeSource(noise.getBiomeSource()), noise.generatorSettings());
    }

    public ChunkGenerator inner() {
        return this.inner;
    }

    public OverworldSlice slice() {
        return this.slice;
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
        this.inner.createStructures(access, state, structures, chunk, templates);
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        this.inner.createReferences(level, structures, chunk);
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
        CompletableFuture<ChunkAccess> filled = this.inner.fillFromNoise(blender, randomState, structureManager, chunk);
        if (!this.isOceanBackfillActive()) {
            return filled;
        }
        return filled.thenApply(generated -> {
            this.backfillExcludedColumns(generated, randomState);
            return generated;
        });
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        this.inner.buildSurface(level, structureManager, randomState, chunk);
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
        this.inner.applyCarvers(level, seed, randomState, biomeManager, structureManager, chunk, step);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        this.inner.applyBiomeDecoration(level, chunk, structures);
        if (this.slice == OverworldSlice.SURFACE) {
            this.sealSlice(chunk);
            this.reprimeHeightmaps(chunk);
            return;
        }
        this.relocateSlice(level, chunk);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        if (this.slice == OverworldSlice.SURFACE) {
            this.inner.spawnOriginalMobs(level);
        }
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
        if (this.slice != OverworldSlice.SURFACE) {
            return this.columnBaseHeight(x, z, type, height, randomState);
        }
        int minY = height.getMinBuildHeight();
        int targetMin = this.slice.targetMinY();
        int targetMaxExclusive = this.slice.targetMaxExclusiveY();
        int mapped = this.inner.getBaseHeight(x, z, type, height, randomState);
        if (mapped > targetMaxExclusive) {
            mapped = targetMaxExclusive;
        }
        if (mapped < targetMin) {
            int sealY = this.slice.sealY();
            if (type.isOpaque().test(Blocks.BEDROCK.defaultBlockState()) && sealY >= minY) {
                return sealY + 1;
            }
            return minY;
        }
        return mapped;
    }

    private int columnBaseHeight(int x, int z, Types type, LevelHeightAccessor height, RandomState randomState) {
        NoiseColumn column = this.getBaseColumn(x, z, height, randomState);
        int minY = height.getMinBuildHeight();
        int maxY = height.getMaxBuildHeight() - 1;
        for (int y = maxY; y >= minY; y--) {
            BlockState state = column.getBlock(y);
            if (type.isOpaque().test(state)) {
                return y + 1;
            }
        }
        return minY;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState randomState) {
        NoiseColumn source = this.isOceanBackfillActive()
                ? this.backfilledSourceColumn(x, z, height, randomState)
                : this.inner.getBaseColumn(x, z, height, randomState);
        int minY = height.getMinBuildHeight();
        int depth = height.getHeight();
        BlockState[] states = new BlockState[depth];
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int i = 0; i < depth; i++) {
            int targetY = minY + i;
            int sourceY = this.slice.toSourceY(targetY);
            BlockState state = this.slice.containsSourceY(sourceY) ? source.getBlock(sourceY) : air;
            states[i] = this.sealedState(targetY, state, bedrock, air);
        }
        return new NoiseColumn(minY, states);
    }

    /**
     * True only for the underground slice whose delegate carries the ocean filter, the same
     * gate as {@link #applyUndergroundOceanFilter}. Gating on the filter (instead of the
     * slice alone) keeps biome label and terrain consistent: a subclass delegate that skips
     * the filter keeps its original terrain rather than ending up with filled rock under an
     * ocean biome label.
     */
    private boolean isOceanBackfillActive() {
        return this.slice == OverworldSlice.UNDERGROUND
                && this.inner instanceof NoiseBasedChunkGenerator noise
                && noise.getBiomeSource() instanceof OceanFilteredBiomeSource;
    }

    /** The pre-filter biome source, used to detect columns the filter rewrites. */
    private BiomeSource unfilteredBiomeSource() {
        NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) this.inner;
        BiomeSource source = noise.getBiomeSource();
        return source instanceof OceanFilteredBiomeSource filtered ? filtered.inner() : source;
    }

    private boolean isExcludedColumn(BiomeSource detector, Climate.Sampler sampler, int blockX, int blockZ, int seaLevel) {
        Holder<Biome> biome = detector.getNoiseBiome(
                QuartPos.fromBlock(blockX),
                QuartPos.fromBlock(seaLevel),
                QuartPos.fromBlock(blockZ),
                sampler);
        return OceanFilteredBiomeSource.isExcludedBiome(biome);
    }

    /**
     * Underground ocean-terrain backfill (no density surgery). The ocean bowl shape comes
     * from the overworld {@code NoiseSettings} density field, which never reads the biome
     * source, so swapping the biome label alone leaves trench walls and sea water behind.
     * For columns the filter rewrites, every air/water cell at or below sea level becomes
     * strata rock ({@link #strataFill}), i.e. the content the nearest land column holds in
     * this source window (solid stone/deepslate: every land surface sits above the window
     * top). Existing solids are kept, preserving veins, bedrock and bowl walls; later stages
     * (surface rules on the plains biome, carvers, ore decoration) then treat the column as
     * ordinary land.
     */
    private void backfillExcludedColumns(ChunkAccess chunk, RandomState randomState) {
        NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) this.inner;
        int seaLevel = noise.getSeaLevel();
        BiomeSource detector = this.unfilteredBiomeSource();
        Climate.Sampler sampler = randomState.sampler();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        boolean touched = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx;
                int z = minZ + lz;
                if (!this.isExcludedColumn(detector, sampler, x, z, seaLevel)) {
                    continue;
                }
                for (int y = minY; y <= seaLevel; y++) {
                    BlockState current = chunk.getBlockState(cursor.set(x, y, z));
                    if (shouldBackfill(current)) {
                        chunk.setBlockState(cursor, strataFill(y), false);
                        touched = true;
                    }
                }
            }
        }
        if (touched) {
            this.reprimeHeightmaps(chunk);
        }
    }

    /**
     * Same backfill applied to a sampled source column, so {@link #getBaseColumn} (and
     * everything derived from it: underground {@link #getBaseHeight}, seam height sampling)
     * sees the filled terrain instead of the raw ocean bowl.
     */
    private NoiseColumn backfilledSourceColumn(int x, int z, LevelHeightAccessor height, RandomState randomState) {
        NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) this.inner;
        NoiseColumn source = noise.getBaseColumn(x, z, height, randomState);
        int seaLevel = noise.getSeaLevel();
        if (!this.isExcludedColumn(this.unfilteredBiomeSource(), randomState.sampler(), x, z, seaLevel)) {
            return source;
        }
        int minY = height.getMinBuildHeight();
        int depth = height.getHeight();
        BlockState[] states = new BlockState[depth];
        for (int i = 0; i < depth; i++) {
            int y = minY + i;
            BlockState state = source.getBlock(y);
            states[i] = y <= seaLevel && shouldBackfill(state)
                    ? strataFill(y)
                    : state;
        }
        return new NoiseColumn(minY, states);
    }

    static BlockState strataFill(int y) {
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    /** Air and water (still + flowing; tag covers both) are replaced; lava and every solid stay. */
    static boolean shouldBackfill(BlockState state) {
        return state.isAir() || state.getFluidState().is(FluidTags.WATER);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("dimblend overworld slice=" + this.slice.serializedName() + " offset=" + this.slice.yOffset());
        this.inner.addDebugScreenInfo(info, randomState, pos.offset(0, -this.slice.yOffset(), 0));
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> lookup, RandomState randomState, long seed) {
        return this.inner.createState(lookup, randomState, seed);
    }

    private void relocateSlice(WorldGenLevel level, ChunkAccess chunk) {
        if (this.slice.yOffset() == 0) {
            this.sealSlice(chunk);
            this.reprimeHeightmaps(chunk);
            return;
        }
        int sourceMin = Math.max(this.slice.sourceMinY(), chunk.getMinBuildHeight());
        int sourceMaxExclusive = Math.min(this.slice.sourceMaxExclusiveY(), chunk.getMaxBuildHeight());
        if (sourceMin >= sourceMaxExclusive) {
            this.sealSlice(chunk);
            return;
        }
        int depth = sourceMaxExclusive - sourceMin;
        BlockState[][][] blocks = new BlockState[16][16][depth];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int dy = 0; dy < depth; dy++) {
                    int sourceY = sourceMin + dy;
                    blocks[lx][lz][dy] = chunk.getBlockState(cursor.set(minX + lx, sourceY, minZ + lz));
                }
            }
        }
        Holder<Biome>[][][] biomes = snapshotBiomes(chunk);
        HolderLookup.Provider provider = level.registryAccess();
        Set<BlockPos> bePositions = chunk.getBlockEntitiesPos();
        List<CompoundTag> beTags = new ArrayList<>();
        for (BlockPos pos : bePositions) {
            if (!this.slice.containsSourceY(pos.getY())) {
                continue;
            }
            CompoundTag tag = chunk.getBlockEntityNbtForSaving(pos, provider);
            if (tag != null) {
                beTags.add(tag);
            }
        }
        for (BlockPos pos : bePositions) {
            chunk.removeBlockEntity(pos);
        }
        if (chunk instanceof ProtoChunk protoChunk) {
            Iterator<CompoundTag> entityTags = protoChunk.getEntities().iterator();
            while (entityTags.hasNext()) {
                CompoundTag entityTag = entityTags.next();
                ListTag posTag = entityTag.getList("Pos", 6);
                if (posTag.size() == 3 && this.slice.containsSourceY(Mth.floor(posTag.getDouble(1)))) {
                    posTag.set(1, DoubleTag.valueOf(posTag.getDouble(1) + this.slice.yOffset()));
                } else {
                    entityTags.remove();
                }
            }
        }

        this.clearColumn(chunk, air());
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int dy = 0; dy < depth; dy++) {
                    int targetY = this.slice.toTargetY(sourceMin + dy);
                    if (targetY < chunk.getMinBuildHeight() || targetY >= chunk.getMaxBuildHeight()) {
                        continue;
                    }
                    chunk.setBlockState(cursor.set(minX + lx, targetY, minZ + lz), blocks[lx][lz][dy], false);
                }
            }
        }
        for (CompoundTag tag : beTags) {
            int targetY = this.slice.toTargetY(tag.getInt("y"));
            if (targetY < chunk.getMinBuildHeight() || targetY >= chunk.getMaxBuildHeight()) {
                continue;
            }
            tag.putInt("y", targetY);
            chunk.setBlockEntityNbt(tag);
        }
        this.writeBiomes(chunk, biomes);
        this.shiftPostProcessing(chunk);
        this.sealSlice(chunk);
        this.reprimeHeightmaps(chunk);
    }

    private Holder<Biome>[][][] snapshotBiomes(ChunkAccess chunk) {
        int quartX0 = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int quartZ0 = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        int minQuartY = QuartPos.fromBlock(chunk.getMinBuildHeight());
        int quartHeight = QuartPos.fromBlock(chunk.getHeight());
        Holder<Biome>[][][] biomes = new Holder[4][4][quartHeight];
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qy = 0; qy < quartHeight; qy++) {
                    biomes[qx][qz][qy] = chunk.getNoiseBiome(quartX0 + qx, minQuartY + qy, quartZ0 + qz);
                }
            }
        }
        return biomes;
    }

    private void writeBiomes(ChunkAccess chunk, Holder<Biome>[][][] biomes) {
        int quartX0 = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int quartZ0 = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        int minQuartY = QuartPos.fromBlock(chunk.getMinBuildHeight());
        int quartHeight = biomes[0][0].length;
        int quartOffset = QuartPos.fromBlock(this.slice.yOffset());
        Holder<Biome> edge = biomes[0][0][0];
        LevelHeightAccessor height = chunk.getHeightAccessorForGeneration();
        for (int sectionY = height.getMinSection(); sectionY < height.getMaxSection(); sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            int sectionQuartY = QuartPos.fromSection(sectionY);
            section.fillBiomesFromNoise(
                    (qx, qy, qz, sampler) -> {
                        int sourceQuartY = qy - quartOffset;
                        int localY = sourceQuartY - minQuartY;
                        if (localY < 0 || localY >= quartHeight) {
                            return edge;
                        }
                        int localX = qx - quartX0;
                        int localZ = qz - quartZ0;
                        if (localX < 0 || localX >= 4 || localZ < 0 || localZ >= 4) {
                            return edge;
                        }
                        return biomes[localX][localZ][localY];
                    },
                    null,
                    quartX0,
                    sectionQuartY,
                    quartZ0
            );
        }
    }

    private void clearColumn(ChunkAccess chunk, BlockState air) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = minY; y <= maxY; y++) {
                    chunk.setBlockState(cursor.set(minX + lx, y, minZ + lz), air, false);
                }
            }
        }
    }

    private void sealSlice(ChunkAccess chunk) {
        BlockState air = air();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        int targetMin = this.slice.targetMinY();
        int targetMaxExclusive = this.slice.targetMaxExclusiveY();
        int sealY = this.slice.sealY();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(minX + lx, y, minZ + lz);
                    BlockState current = chunk.getBlockState(cursor);
                    BlockState sealed = this.sealedState(y, current, bedrock, air, targetMin, targetMaxExclusive, sealY);
                    if (sealed != current) {
                        chunk.setBlockState(cursor, sealed, false);
                    }
                }
            }
        }
    }

    private BlockState sealedState(int targetY, BlockState current, BlockState bedrock, BlockState air) {
        return this.sealedState(
                targetY,
                current,
                bedrock,
                air,
                this.slice.targetMinY(),
                this.slice.targetMaxExclusiveY(),
                this.slice.sealY()
        );
    }

    private BlockState sealedState(
            int targetY,
            BlockState current,
            BlockState bedrock,
            BlockState air,
            int targetMin,
            int targetMaxExclusive,
            int sealY
    ) {
        if (this.slice.isSealY(targetY)) {
            return bedrock;
        }
        if (targetY < targetMin || targetY >= targetMaxExclusive) {
            return air;
        }
        return current;
    }


    private int sealedHeight() {
        return this.slice.topBedrock()
                ? this.slice.targetMaxExclusiveY()
                : this.slice.targetMinY();
    }

    private void reprimeHeightmaps(ChunkAccess chunk) {
        EnumSet<Types> primed = EnumSet.noneOf(Types.class);
        for (Types type : Types.values()) {
            if (chunk.hasPrimedHeightmap(type)) {
                primed.add(type);
            }
        }
        if (!primed.isEmpty()) {
            Heightmap.primeHeightmaps(chunk, primed);
        }
    }

    private void shiftPostProcessing(ChunkAccess chunk) {
        if (!(chunk instanceof ProtoChunk proto)) {
            return;
        }
        it.unimi.dsi.fastutil.shorts.ShortList[] lists = proto.getPostProcessing();
        ChunkPos pos = proto.getPos();
        java.util.ArrayList<BlockPos> sourceMarks = new java.util.ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < lists.length; sectionIndex++) {
            it.unimi.dsi.fastutil.shorts.ShortList source = lists[sectionIndex];
            if (source == null || source.isEmpty()) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            for (short packed : source.toShortArray()) {
                sourceMarks.add(ProtoChunk.unpackOffsetCoordinates(packed, sectionY, pos));
            }
            source.clear();
        }
        for (BlockPos sourcePos : sourceMarks) {
            int targetY = this.slice.toTargetY(sourcePos.getY());
            if (targetY < chunk.getMinBuildHeight() || targetY >= chunk.getMaxBuildHeight()) {
                continue;
            }
            proto.markPosForPostprocessing(new BlockPos(sourcePos.getX(), targetY, sourcePos.getZ()));
        }
    }

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }
}
