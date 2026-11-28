package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.MapCodec;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * W2: the wall went away, its truth table did not. Every seam predicate must agree with the
 * pre-W2 boundary-column rule, and the two runtime predicates (fluid crossing, box overlap) must
 * be directional/geometric rather than "there is a wall block here".
 */
class BandSeamTest {
    private static final int BAND = BandIndex.DEFAULT_BAND_SIZE;

    /**
     * Loading {@link ChunkGenerator} pulls {@code ChunkGenerator.CODEC} into
     * {@code BuiltInRegistries}, which needs the bootstrap flag; no registry content is involved,
     * so {@code Bootstrap.bootStrap()} is deliberately not run (data files are absent from the
     * test classpath). Same shim as {@link RotatingChunkGeneratorStartupTest}.
     */
    @BeforeAll
    static void prepareMinecraftStatics() throws ReflectiveOperationException {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Field bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        bootstrapped.setBoolean(null, true);
    }

    @AfterEach
    void restoreDefault() {
        BandSeam.setFramelessSeam(true);
    }

    @Test
    void framelessIsTheDefaultAndRollbackIsSwitchable() {
        assertTrue(BandSeam.isFramelessSeam(), "W2 ships frameless");
        BandSeam.setFramelessSeam(false);
        assertFalse(BandSeam.isFramelessSeam());
    }

    @Test
    void ownsSeamColumnMatchesTheLegacyBoundaryTruthTable() {
        BandLayout layout = layout();
        for (int blockX = -2 * BAND - 3; blockX <= 2 * BAND + 3; blockX++) {
            boolean expected = Math.floorMod(blockX, BAND) == 0
                    && !layout.sameLaneAcrossBoundary(blockX, BAND);
            assertEquals(expected, BandSeam.ownsSeamColumn(layout, BAND, blockX),
                    "seam truth changed at " + blockX);
        }
        assertFalse(BandSeam.ownsSeamColumn(layout, BAND, BAND + 1));
        assertFalse(BandSeam.ownsSeamColumn(layout, BAND, 17));
    }

    @Test
    void sameLaneBoundariesStayOpenAndDifferentLaneBoundariesAreSeams() {
        BandLayout layout = layout();
        int owned = Integer.MIN_VALUE;
        int same = Integer.MIN_VALUE;
        for (int k = -8; k <= 8; k++) {
            int blockX = k * BAND;
            if (layout.sameLaneAcrossBoundary(blockX, BAND)) {
                same = blockX;
            } else {
                owned = blockX;
            }
        }
        assertTrue(owned != Integer.MIN_VALUE, "test layout has no different-lane boundary");
        assertTrue(same != Integer.MIN_VALUE, "test layout has no same-lane boundary");
        assertTrue(BandSeam.ownsSeamColumn(layout, BAND, owned));
        assertFalse(BandSeam.ownsSeamColumn(layout, BAND, same));
    }

    @Test
    void crossesSeamBlocksBothDirectionsAndOnlySingleBlockSteps() {
        BandLayout layout = layout();
        int seam = differentLaneSeam(layout);
        assertTrue(BandSeam.crossesSeamX(layout, BAND, seam - 1, seam), "west→east");
        assertTrue(BandSeam.crossesSeamX(layout, BAND, seam, seam - 1), "east→west");
        assertFalse(BandSeam.crossesSeamX(layout, BAND, seam - 2, seam - 1), "not at the seam");
        assertFalse(BandSeam.crossesSeamX(layout, BAND, seam + 1, seam + 2), "past the seam");
        assertFalse(BandSeam.crossesSeamX(layout, BAND, seam, seam), "no step");
        assertFalse(BandSeam.crossesSeamX(layout, BAND, seam - 2, seam), "two-block jump");
        int open = sameLaneSeam(layout);
        assertFalse(BandSeam.crossesSeamX(layout, BAND, open - 1, open),
                "same-lane boundaries are open corridors");
        assertFalse(BandSeam.crossesSeamX(layout, BAND, open, open - 1));
    }

    @Test
    void overlappedSeamColumnHitsOnlyTheVaultCrossSection() {
        BandLayout layout = layout();
        int seam = differentLaneSeam(layout);
        assertEquals(seam, BandSeam.overlappedSeamColumn(layout, BAND,
                seam - 0.25, seam + 0.25, 62.0, 82.0, -1.0, 1.0));
        assertEquals(seam, BandSeam.overlappedSeamColumn(layout, BAND,
                seam, seam + 1.0, 63.0, 81.0, 0.0, 1.0), "box starting on the slab still overlaps");
        assertEquals(-1, BandSeam.overlappedSeamColumn(layout, BAND,
                seam - 1.0, seam, 63.0, 81.0, 0.0, 1.0), "box ending on the slab edge does not");
        assertEquals(-1, BandSeam.overlappedSeamColumn(layout, BAND,
                seam - 0.25, seam + 0.25, 82.1, 90.0, -1.0, 1.0), "above the apex");
        assertEquals(-1, BandSeam.overlappedSeamColumn(layout, BAND,
                seam - 0.25, seam + 0.25, 40.0, 62.9, -1.0, 1.0), "below the vault floor");
        assertEquals(-1, BandSeam.overlappedSeamColumn(layout, BAND,
                seam - 0.25, seam + 0.25, 63.0, 81.0, -20.0, -10.2), "outside the corridor Z span");
        assertEquals(-1, BandSeam.overlappedSeamColumn(layout, BAND,
                seam - 0.25, seam + 0.25, 63.0, 81.0, 10.1, 20.0), "outside the corridor Z span");
        int open = sameLaneSeam(layout);
        assertEquals(-1, BandSeam.overlappedSeamColumn(layout, BAND,
                open - 0.25, open + 0.25, 63.0, 81.0, -1.0, 1.0),
                "no gate on a same-lane boundary");
    }

    @Test
    void seamMaxDyTakesTheLowerOfBothLanes() {
        // Every delegate in the real rotating layout yields VAULT_APEX_DY today (surface and
        // underground slices both contain TRACK_Y); the invariant is the min, not the max.
        RotatingChunkGenerator rotating = rotating();
        int seam = differentLaneSeam(layout());
        assertEquals(OakTrackCorridor.VAULT_APEX_DY, BandSeam.seamMaxDy(rotating, seam));
        assertEquals(Math.min(
                        OakTrackCorridor.vaultMaxDy(rotating.delegateForBlockX(seam - 1)),
                        OakTrackCorridor.vaultMaxDy(rotating.delegateForBlockX(seam))),
                BandSeam.seamMaxDy(rotating, seam));
    }

    private static int differentLaneSeam(BandLayout layout) {
        for (int k = 1; k <= 8; k++) {
            int blockX = k * BAND;
            if (!layout.sameLaneAcrossBoundary(blockX, BAND)) {
                return blockX;
            }
        }
        throw new AssertionError("test layout has no different-lane seam");
    }

    private static int sameLaneSeam(BandLayout layout) {
        for (int k = -8; k <= 8; k++) {
            int blockX = k * BAND;
            if (blockX != 0 && layout.sameLaneAcrossBoundary(blockX, BAND)) {
                return blockX;
            }
        }
        if (layout.sameLaneAcrossBoundary(0, BAND)) {
            return 0;
        }
        throw new AssertionError("test layout has no same-lane boundary");
    }

    private static BandLayout layout() {
        ChunkGenerator inner = new StubGenerator("overworld");
        return new BandLayout(List.of(
                SlicedOverworldChunkGenerator.parse(inner, OverworldSlice.SURFACE),
                SlicedOverworldChunkGenerator.parse(inner, OverworldSlice.UNDERGROUND),
                new StubGenerator("nether"),
                new StubGenerator("end")), 1234567L);
    }

    private static RotatingChunkGenerator rotating() {
        ChunkGenerator inner = new StubGenerator("overworld");
        return new RotatingChunkGenerator(List.of(
                SlicedOverworldChunkGenerator.parse(inner, OverworldSlice.SURFACE),
                SlicedOverworldChunkGenerator.parse(inner, OverworldSlice.UNDERGROUND),
                new StubGenerator("nether"),
                new StubGenerator("end")), BAND);
    }

    /**
     * Minimal generator used only for its data-fixer name: {@link BandLayout#laneName} maps
     * {@code minecraft:overworld/nether/end}. No world, registry bootstrap or biome source needed
     * because only classification and the pure seam predicates run.
     */
    private static final class StubGenerator extends ChunkGenerator {
        private final ResourceKey<MapCodec<? extends ChunkGenerator>> key;

        StubGenerator(String path) {
            super(new StubBiomeSource());
            this.key = ResourceKey.create(Registries.CHUNK_GENERATOR,
                    ResourceLocation.withDefaultNamespace(path));
        }

        @Override
        protected MapCodec<? extends ChunkGenerator> codec() {
            return null;
        }

        @Override
        public Optional<ResourceKey<MapCodec<? extends ChunkGenerator>>> getTypeNameForDataFixer() {
            return Optional.of(this.key);
        }

        @Override
        public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                BiomeManager biomeManager, StructureManager structures, ChunkAccess chunk,
                GenerationStep.Carving step) {
        }

        @Override
        public void buildSurface(WorldGenRegion level, StructureManager structures,
                RandomState random, ChunkAccess chunk) {
        }

        @Override
        public void spawnOriginalMobs(WorldGenRegion level) {
        }

        @Override
        public int getGenDepth() {
            return 384;
        }

        @Override
        public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
                StructureManager structures, ChunkAccess chunk) {
            return CompletableFuture.completedFuture(chunk);
        }

        @Override
        public int getSeaLevel() {
            return 63;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getBaseHeight(int x, int z, Heightmap.Types type,
                LevelHeightAccessor level, RandomState random) {
            return 63;
        }

        @Override
        public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
            return new NoiseColumn(level.getMinBuildHeight(), new net.minecraft.world.level.block.state.BlockState[0]);
        }

        @Override
        public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        }

        @Override
        public void createStructures(RegistryAccess access, ChunkGeneratorStructureState state,
                StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) {
        }

        @Override
        public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        }
    }

    /** Only needed so {@code RotatingBiomeSource} can copy a non-null list; never queried. */
    private static final class StubBiomeSource extends net.minecraft.world.level.biome.BiomeSource {
        @Override
        protected MapCodec<? extends net.minecraft.world.level.biome.BiomeSource> codec() {
            return null;
        }

        @Override
        protected java.util.stream.Stream<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>
                collectPossibleBiomes() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(
                int x, int y, int z, net.minecraft.world.level.biome.Climate.Sampler sampler) {
            throw new UnsupportedOperationException("stub biome source");
        }
    }
}
