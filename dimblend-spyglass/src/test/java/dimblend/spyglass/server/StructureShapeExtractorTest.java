package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import dimblend.spyglass.structure.StructureShape;
import dimblend.spyglass.structure.StructureShapeCodec;
import dimblend.spyglass.test.ChunkNbtFixture;
import dimblend.spyglass.test.RegionFileFixture;
import dimblend.spyglass.test.TestBiomes;
import dimblend.spyglass.test.VanillaTestBootstrap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises shape extraction against real region files.
 *
 * <p>The fixtures are written by {@link RegionFileFixture}/{@link ChunkNbtFixture} exactly like the
 * game writes them (same codecs, same header layout), so these tests cover the production read path:
 * status gate, piece filter, fluid filter, block cap, dominant biome and region-header revision.
 */
class StructureShapeExtractorTest {
    static {
        VanillaTestBootstrap.ensure();
    }

    private static final ResourceLocation HOUSE = ResourceLocation.fromNamespaceAndPath("test", "house");
    private static final Registry<Biome> BIOMES = TestBiomes.create();

    @Test
    void skipsAStructureWhenOneCoveringChunkIsBelowFeatures(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag generated = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        ChunkNbtFixture.addSection(generated, section(0, BIOMES));
        regions.writeChunk(0, 0, generated, 1000);
        // The neighbour is only at carvers: its blocks do not exist yet.
        regions.writeChunk(1, 0, ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 1, 0, "minecraft:carvers"), 2000);

        StructureShapeExtractor.Extraction extraction = extract(regions, piece(0, 0, 0, 17, 15, 15));

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().notGenerated());
        assertEquals(0, extraction.stats().readFailed());
    }

    @Test
    void keepsOnlyBlocksInsideOneOfThePieceBoxes(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.setBlock(states, local(11, 1, 11), Blocks.STONE.defaultBlockState());
        // Inside the union bounds of the two pieces, but inside neither piece.
        ChunkNbtFixture.setBlock(states, local(7, 1, 7), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        regions.writeChunk(0, 0, chunk, 10);

        StructureShapeExtractor.Extraction extraction = extract(
                regions,
                piece(0, 0, 0, 3, 15, 3),
                piece(10, 0, 10, 13, 15, 13));

        assertEquals(1, extraction.shapes().size());
        StructureShape shape = resolve(extraction.shapes().get(0));
        assertEquals(2, shape.blockCount());
        assertEquals(Blocks.STONE.defaultBlockState(), shape.getBlockState(1, 1, 1));
        assertEquals(Blocks.STONE.defaultBlockState(), shape.getBlockState(11, 1, 11));
        assertEquals(Blocks.AIR.defaultBlockState(), shape.getBlockState(7, 1, 7));
        assertTrue(shape.getBlockState(7, 1, 7).isAir());
    }

    @Test
    void skipsLiquidBlocksButKeepsWaterloggedBlocks(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        BlockState waterloggedStairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.WATERLOGGED, true);
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.WATER.defaultBlockState());
        ChunkNbtFixture.setBlock(states, local(2, 1, 2), waterloggedStairs);
        ChunkNbtFixture.setBlock(states, local(3, 1, 3), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        regions.writeChunk(0, 0, chunk, 10);

        StructureShape shape = resolve(extract(regions, piece(0, 0, 0, 15, 15, 15)).shapes().get(0));

        assertEquals(2, shape.blockCount());
        assertTrue(shape.getBlockState(1, 1, 1).isAir(), "a pure fluid must not be meshed as a block");
        assertEquals(waterloggedStairs, shape.getBlockState(2, 1, 2));
        assertTrue(waterloggedStairs.getValue(StairBlock.WATERLOGGED));
        assertEquals(Blocks.STONE.defaultBlockState(), shape.getBlockState(3, 1, 3));
    }

    @Test
    void dropsStructuresAboveTheBlockCap(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:features");
        // 65 solid sections = 266,240 blocks, just above the 262,144 cap. Single-palette sections
        // keep the NBT tiny.
        for (int sectionY = -32; sectionY <= 32; sectionY++) {
            PalettedContainer<BlockState> states =
                    ChunkNbtFixture.filledBlockStates(Blocks.STONE.defaultBlockState());
            ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(sectionY, states, biomes(), BIOMES));
        }
        regions.writeChunk(0, 0, chunk, 10);

        StructureShapeExtractor.Extraction extraction =
                extract(regions, piece(0, -512, 0, 15, 527, 15));

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().tooManyBlocks());
    }

    @Test
    void usesTheDominantBiomeOfTheUsedSections(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        // Section 0 contributes one block but is entirely desert-biome, section 1 is plains: one
        // section each, 64 cells each, tie -> deterministic key order (desert < plains).
        PalettedContainer<BlockState> lower = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(lower, local(1, 1, 1), Blocks.STONE.defaultBlockState());
        PalettedContainer<Holder<Biome>> lowerBiomes = ChunkNbtFixture.biomes(BIOMES, TestBiomes.DESERT);
        PalettedContainer<BlockState> upper = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(upper, local(1, 1, 1), Blocks.STONE.defaultBlockState());
        PalettedContainer<Holder<Biome>> upperBiomes = ChunkNbtFixture.biomes(BIOMES, TestBiomes.PLAINS);
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, lower, lowerBiomes, BIOMES));
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(1, upper, upperBiomes, BIOMES));
        regions.writeChunk(0, 0, chunk, 10);

        StructureShape shape = resolve(extract(regions, piece(0, 0, 0, 15, 31, 15)).shapes().get(0));

        assertEquals(TestBiomes.DESERT, shape.biome().orElseThrow().unwrapKey().orElseThrow());
        assertEquals(2, shape.blockCount());
    }

    @Test
    void dominantBiomeWeighsEveryUsedSectionEqually(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        for (int sectionY = 0; sectionY < 3; sectionY++) {
            PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
            ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
            ResourceKey<Biome> biome = sectionY < 2 ? TestBiomes.PLAINS : TestBiomes.OCEAN;
            ChunkNbtFixture.addSection(
                    chunk,
                    ChunkNbtFixture.section(sectionY, states, ChunkNbtFixture.biomes(BIOMES, biome), BIOMES));
        }
        regions.writeChunk(0, 0, chunk, 10);

        StructureShape shape = resolve(extract(regions, piece(0, 0, 0, 15, 47, 15)).shapes().get(0));

        assertEquals(TestBiomes.PLAINS, shape.biome().orElseThrow().unwrapKey().orElseThrow());
    }

    @Test
    void takesTheRevisionFromTheRegionHeaderTimestamps(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag first = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        CompoundTag second = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 1, 0, "minecraft:full");
        for (CompoundTag chunk : List.of(first, second)) {
            PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
            ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
            ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        }
        regions.writeChunk(0, 0, first, 1111);
        regions.writeChunk(1, 0, second, 2222);

        SpyglassServerShapeCache cache = new SpyglassServerShapeCache();
        StructureShapeExtractor.Extraction extraction =
                extract(regions, cache, piece(0, 0, 0, 17, 15, 15));
        assertEquals(1, extraction.shapes().size());
        assertEquals(2222, extraction.shapes().get(0).revision());
        assertEquals(0, extraction.stats().cacheHits());

        // Same files, same revision: the encoded shape is reused, no section is read again.
        StructureShapeExtractor.Extraction cached =
                extract(regions, cache, piece(0, 0, 0, 17, 15, 15));
        assertEquals(1, cached.stats().cacheHits());
        assertEquals(extraction.shapes().get(0), cached.shapes().get(0));

        // Re-saving the newest chunk changes the revision, so the server-side cache misses.
        regions.writeChunk(1, 0, second, 3333);
        StructureShapeExtractor.Extraction resaved =
                extract(regions, cache, piece(0, 0, 0, 17, 15, 15));
        assertEquals(3333, resaved.shapes().get(0).revision());
        assertEquals(0, resaved.stats().cacheHits());
    }

    @Test
    void linksTheScannedStartToTheShapeOfTheSameKey(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 18, 0, "minecraft:full");
        // Far enough from the origin position to be inside the snapshot ring after limiting.
        ChunkNbtFixture.addStart(chunk, HOUSE, List.of(new int[] {300, 0, 0, 303, 15, 3}));
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(14, 1, 1), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        regions.writeChunk(18, 0, chunk, 77);

        RegionScanResult scan = ReadOnlyRegionScanner.scan(
                context(regions.regionDirectory()),
                List.of(new StructureCandidate(HOUSE, 18, 0)));
        assertEquals(1, scan.outlines().size(), "scan errors: " + scan.errors());
        SpyglassSnapshotLimiter.LimitResult limited =
                SpyglassSnapshotLimiter.limit(0, 0, 0.0, scan.outlines());
        assertEquals(1, limited.selected().size());
        StructureShapeExtractor.Extraction extraction = StructureShapeExtractor.extract(
                context(regions.regionDirectory()),
                BIOMES,
                limited.selected(),
                new SpyglassServerShapeCache(),
                new RegionReadErrors());

        assertEquals(1, extraction.shapes().size());
        StructureKey expectedKey = new StructureKey(HOUSE, 18, 0);
        assertEquals(expectedKey, scan.outlines().get(0).key());
        assertEquals(expectedKey, extraction.shapes().get(0).key());
        StructureShape shape = resolve(extraction.shapes().get(0));
        assertEquals(Blocks.STONE.defaultBlockState(), shape.getBlockState(302, 1, 1));
    }

    @Test
    void productionLimitsMatchTheDocumentedCaps() {
        StructureShapeExtractor.Limits limits = StructureShapeExtractor.Limits.production();

        assertEquals(16_384, limits.maxPiecesPerStructure());
        assertEquals(16_777_216L, limits.maxVisitedCells());
        assertEquals(32L << 20, limits.maxEncodedBytes());
    }

    @Test
    void dropsStructuresWithMorePiecesThanTheExtractionCap(@TempDir Path tempDir) {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        OutlineBox box = piece(0, 0, 0, 15, 15, 15);
        List<OutlineBox> pieces = Collections.nCopies(
                StructureShapeExtractor.MAX_SHAPE_PIECES + 1, box);

        StructureShapeExtractor.Extraction extraction = extract(
                regions,
                List.of(limited(new StructureKey(HOUSE, 0, 0), pieces)),
                StructureShapeExtractor.Limits.production(),
                () -> false);

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().unencodable());
        assertEquals(0, extraction.stats().extracted());
    }

    @Test
    void abortsAStructureThatVisitsMoreCellsThanTheBudget(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:features");
        // The budget counts every visited cell, air included. One duplicate section entry per block
        // of the budget keeps the fixture small while still exercising the production bound: every
        // duplicate intersects the piece and costs its full 4,096 cells.
        int sections = (int) (StructureShapeExtractor.MAX_VISITED_CELLS
                / ChunkNbtFixture.SECTION_VOLUME) + 1;
        CompoundTag airSection =
                ChunkNbtFixture.section(0, ChunkNbtFixture.blockStates(), biomes(), BIOMES);
        for (int i = 0; i < sections; i++) {
            ChunkNbtFixture.addSection(chunk, airSection.copy());
        }
        regions.writeChunk(0, 0, chunk, 10);

        StructureShapeExtractor.Extraction extraction = extract(
                regions,
                List.of(limited(new StructureKey(HOUSE, 0, 0), List.of(piece(0, 0, 0, 15, 15, 15)))),
                StructureShapeExtractor.Limits.production(),
                () -> false);

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().unencodable());
        assertEquals(0, extraction.stats().extracted());
    }

    @Test
    void interruptionEndsTheExtraction(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        regions.writeChunk(0, 0, chunk, 10);

        Thread.currentThread().interrupt();
        try {
            StructureShapeExtractor.Extraction extraction =
                    extract(regions, piece(0, 0, 0, 15, 15, 15));

            assertTrue(extraction.shapes().isEmpty());
            assertEquals(0, extraction.stats().extracted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationStopsTheScanAtTheNextSection(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        for (int sectionY = 0; sectionY < 3; sectionY++) {
            PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
            ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
            ChunkNbtFixture.addSection(
                    chunk, ChunkNbtFixture.section(sectionY, states, biomes(), BIOMES));
        }
        regions.writeChunk(0, 0, chunk, 10);

        // Structure, region and chunk checks pass; the first section check cancels the extraction.
        AtomicInteger checks = new AtomicInteger();
        StructureShapeExtractor.Extraction extraction = extract(
                regions,
                List.of(limited(new StructureKey(HOUSE, 0, 0), List.of(piece(0, 0, 0, 15, 47, 15)))),
                StructureShapeExtractor.Limits.production(),
                () -> checks.incrementAndGet() > 5);

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(0, extraction.stats().extracted());
        assertTrue(checks.get() > 5);
    }

    @Test
    void keepsOnlyTheNearestShapesThatFitThePerScanByteBudget(@TempDir Path tempDir)
            throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        for (int chunkX = 0; chunkX < 2; chunkX++) {
            CompoundTag chunk = ChunkNbtFixture.chunk(
                    ChunkNbtFixture.DATA_VERSION, chunkX, 0, "minecraft:full");
            PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
            ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
            ChunkNbtFixture.addSection(
                    chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
            regions.writeChunk(chunkX, 0, chunk, 10);
        }
        StructureKey nearKey = new StructureKey(HOUSE, 0, 0);
        StructureKey farKey = new StructureKey(HOUSE, 1, 0);
        List<SpyglassSnapshotLimiter.LimitedStructure> structures = List.of(
                limited(nearKey, List.of(piece(0, 0, 0, 15, 15, 15))),
                limited(farKey, List.of(piece(16, 0, 0, 31, 15, 15))));

        StructureShapeExtractor.Extraction unbounded = extract(
                regions,
                structures,
                new StructureShapeExtractor.Limits(
                        StructureShapeExtractor.MAX_SHAPE_PIECES,
                        StructureShapeExtractor.MAX_VISITED_CELLS,
                        Long.MAX_VALUE),
                () -> false);
        assertEquals(2, unbounded.shapes().size());
        int firstBodyBytes = unbounded.shapes().get(0).bodyBytes();

        StructureShapeExtractor.Extraction capped = extract(
                regions,
                structures,
                new StructureShapeExtractor.Limits(
                        StructureShapeExtractor.MAX_SHAPE_PIECES,
                        StructureShapeExtractor.MAX_VISITED_CELLS,
                        firstBodyBytes),
                () -> false);

        assertEquals(1, capped.shapes().size());
        assertEquals(nearKey, capped.shapes().get(0).key());
        assertEquals(firstBodyBytes, capped.shapes().get(0).bodyBytes());
    }

    @Test
    void dropsStructuresThatAreFullyBuriedUnderTheSavedSurface(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        // The saved surface is twenty blocks above the structure: nothing can reach it.
        ChunkNbtFixture.flatSurface(chunk, 20);
        regions.writeChunk(0, 0, chunk, 10);

        StructureShapeExtractor.Extraction extraction = extract(regions, piece(0, 0, 0, 15, 15, 15));

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().empty());
        assertEquals(0, extraction.stats().extracted());
    }

    @Test
    void neverShipsBlocksWhenTheChunkHasNoHeightmap(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(1, 1, 1), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        chunk.remove("Heightmaps");
        regions.writeChunk(0, 0, chunk, 10);

        StructureShapeExtractor.Extraction extraction = extract(regions, piece(0, 0, 0, 15, 15, 15));

        // A missing surface closes every column; the block must not be shipped unfiltered.
        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().empty());
    }

    @Test
    void theHeightmapOfTheNeighbouringChunkExposesTheEdgeBlock(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(15, 1, 1), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        // The covering chunk's surface is above the block, so only the ring chunk can expose it.
        ChunkNbtFixture.flatSurface(chunk, 20);
        regions.writeChunk(0, 0, chunk, 10);
        CompoundTag neighbour = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 1, 0, "minecraft:full");
        ChunkNbtFixture.flatSurface(neighbour, 0);
        regions.writeChunk(1, 0, neighbour, 11);

        StructureShapeExtractor.Extraction extraction =
                extract(regions, piece(10, 0, 0, 15, 15, 15));

        assertEquals(1, extraction.shapes().size());
        StructureShape shape = resolve(extraction.shapes().get(0));
        assertEquals(1, shape.blockCount());
        assertEquals(Blocks.STONE.defaultBlockState(), shape.getBlockState(15, 1, 1));
    }

    @Test
    void anUngeneratedRingChunkDoesNotExposeTheEdgeBlock(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(15, 1, 1), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        ChunkNbtFixture.flatSurface(chunk, 20);
        regions.writeChunk(0, 0, chunk, 10);
        // Same flat surface as the exposing neighbour above, but still at carvers: its terrain is not
        // generated, so its columns must stay closed.
        CompoundTag neighbour = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 1, 0, "minecraft:carvers");
        ChunkNbtFixture.flatSurface(neighbour, 0);
        regions.writeChunk(1, 0, neighbour, 11);

        StructureShapeExtractor.Extraction extraction =
                extract(regions, piece(10, 0, 0, 15, 15, 15));

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().empty());
    }

    @Test
    void skipsAStructureWhoseVisibilityVolumeExceedsTheBudget(@TempDir Path tempDir) throws IOException {
        RegionFileFixture regions = new RegionFileFixture(tempDir.resolve("region"));
        CompoundTag chunk = ChunkNbtFixture.chunk(
                ChunkNbtFixture.DATA_VERSION, 0, 0, "minecraft:full");
        PalettedContainer<BlockState> states = ChunkNbtFixture.blockStates();
        ChunkNbtFixture.setBlock(states, local(0, 0, 0), Blocks.STONE.defaultBlockState());
        ChunkNbtFixture.addSection(chunk, ChunkNbtFixture.section(0, states, biomes(), BIOMES));
        regions.writeChunk(0, 0, chunk, 10);

        // Three visited cells fit the budget, but the block's visibility volume (3x3x3) does not.
        StructureShapeExtractor.Extraction extraction = extract(
                regions,
                List.of(limited(new StructureKey(HOUSE, 0, 0), List.of(piece(0, 0, 0, 0, 2, 0)))),
                new StructureShapeExtractor.Limits(
                        StructureShapeExtractor.MAX_SHAPE_PIECES, 8L, Long.MAX_VALUE),
                () -> false);

        assertTrue(extraction.shapes().isEmpty());
        assertEquals(1, extraction.stats().visibilityVolumeTooLarge());
        assertEquals(0, extraction.stats().empty());
    }

    private static SpyglassSnapshotLimiter.LimitedStructure limited(
            StructureKey key, List<OutlineBox> pieces) {
        return new SpyglassSnapshotLimiter.LimitedStructure(
                StructureOutline.of(key, pieces), pieces);
    }

    private static StructureShapeExtractor.Extraction extract(
            RegionFileFixture regions,
            List<SpyglassSnapshotLimiter.LimitedStructure> structures,
            StructureShapeExtractor.Limits limits,
            BooleanSupplier cancelled) {
        return StructureShapeExtractor.extract(
                context(regions.regionDirectory()),
                BIOMES,
                structures,
                new SpyglassServerShapeCache(),
                new RegionReadErrors(),
                limits,
                cancelled);
    }

    private static RegionReadContext context(Path regionDirectory) {
        return new RegionReadContext(
                Level.OVERWORLD,
                ChunkNbtFixture.DATA_VERSION,
                regionDirectory,
                ChunkNbtFixture.MIN_BUILD_HEIGHT,
                ChunkNbtFixture.LEVEL_HEIGHT);
    }

    private static StructureShapeExtractor.Extraction extract(
            RegionFileFixture regions, OutlineBox... pieces) {
        return extract(regions, new SpyglassServerShapeCache(), pieces);
    }

    private static StructureShapeExtractor.Extraction extract(
            RegionFileFixture regions, SpyglassServerShapeCache cache, OutlineBox... pieces) {
        List<OutlineBox> pieceList = List.of(pieces);
        StructureKey key = new StructureKey(HOUSE, 0, 0);
        StructureOutline outline = StructureOutline.of(key, pieceList);
        return StructureShapeExtractor.extract(
                context(regions.regionDirectory()),
                BIOMES,
                List.of(new SpyglassSnapshotLimiter.LimitedStructure(outline, pieceList)),
                cache,
                new RegionReadErrors());
    }

    private static StructureShape resolve(
            EncodedStructureShape encoded) {
        StructureShapeCodec.DecodedShape decoded = StructureShapeCodec.decode(encoded.joinParts());
        BlockState[] palette = new BlockState[decoded.paletteIds().length];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = Block.stateById(decoded.paletteIds()[i]);
            assertNotNull(state, "unknown palette id " + decoded.paletteIds()[i]);
            palette[i] = state;
        }
        Optional<Holder<Biome>> biome = BIOMES
                .getHolder(ResourceKey.create(Registries.BIOME, decoded.biome()))
                .map(holder -> (Holder<Biome>) holder);
        return StructureShape.of(encoded.key(), encoded.revision(), decoded, palette, biome);
    }

    private static CompoundTag section(int sectionY, Registry<Biome> biomes) {
        return ChunkNbtFixture.section(
                sectionY, ChunkNbtFixture.blockStates(), biomes(), biomes);
    }

    private static PalettedContainer<Holder<Biome>> biomes() {
        return ChunkNbtFixture.biomes(BIOMES, TestBiomes.PLAINS);
    }

    private static OutlineBox piece(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new OutlineBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int local(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }
}
