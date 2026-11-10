package dimblend.gametest;

import dimblend.worldgen.CorridorLavaOriginals;
import dimblend.worldgen.CorridorLavaPlan;
import dimblend.worldgen.CorridorLavaSources;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Explicit core registration; no automatic holder or experience-mod dependency. */
@PrefixGameTestTemplate(false)
public final class CorridorLavaSourceGameTests {
    private static final String TEMPLATE = "corridor_lava_empty";
    private static final String BATCH = "corridor_lava";

    private CorridorLavaSourceGameTests() { }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void eachHorizontalDirectionAndDownCanTrigger(GameTestHelper helper) {
        BlockPos source = new BlockPos(8, 70, 0);
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.DOWN}) {
            Map<BlockPos, BlockState> states = new HashMap<>();
            states.put(source, Blocks.LAVA.defaultBlockState());
            states.put(source.relative(direction), Blocks.AIR.defaultBlockState());
            Fixture original = new Fixture(states);
            helper.assertTrue(CorridorLavaSources.canFlowOut(original, source), "outlet " + direction);
            CorridorLavaPlan plan = CorridorLavaSources.plan(original, List.of(source));
            helper.assertTrue(plan.clears(new CorridorLavaPlan.Cell(8, 66, 0)), "inclusive floor " + direction);
            helper.assertTrue(!plan.clears(new CorridorLavaPlan.Cell(8, 70, 0)), "own source unchanged " + direction);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void sealedSourceAndNonAirFluidBarriersDoNotTrigger(GameTestHelper helper) {
        BlockPos source = new BlockPos(8, 70, 0);
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(source, Blocks.LAVA.defaultBlockState());
        states.put(source.above(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(!CorridorLavaSources.canFlowOut(new Fixture(states), source), "up alone is not an outlet");
        for (BlockState barrier : List.of(Blocks.LADDER.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState(),
                Blocks.NETHER_PORTAL.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.LAVA.defaultBlockState())) {
            states.put(source.east(), barrier);
            Fixture original = new Fixture(states);
            helper.assertTrue(!CorridorLavaSources.canFlowOut(original, source), "real fluid barrier " + barrier);
            helper.assertTrue(!CorridorLavaSources.plan(original, List.of(source))
                    .clears(new CorridorLavaPlan.Cell(8, 66, 0)), "sealed source must be unchanged");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void replaceablePlantTriggersButOnlyAirGetsGlass(GameTestHelper helper) {
        BlockPos source = new BlockPos(8, 70, 0);
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(source, Blocks.LAVA.defaultBlockState());
        states.put(source.east(), Blocks.SHORT_GRASS.defaultBlockState());
        Fixture original = new Fixture(states);
        helper.assertTrue(CorridorLavaSources.canFlowOut(original, source), "plant is a valid real-fluid outlet");
        CorridorLavaPlan plantOnly = CorridorLavaSources.plan(original, List.of(source));
        int[] glass = {0};
        plantOnly.forEachGlass(cell -> glass[0]++);
        helper.assertTrue(glass[0] == 0, "replaceability must not turn a plant into glass");
        states.put(source.west(), Blocks.CAVE_AIR.defaultBlockState());
        CorridorLavaPlan withAir = CorridorLavaSources.plan(new Fixture(states), List.of(source));
        withAir.forEachGlass(cell -> {
            helper.assertTrue(cell.equals(new CorridorLavaPlan.Cell(7, 70, 0)), "only cave air becomes glass");
            glass[0]++;
        });
        helper.assertTrue(glass[0] == 1, "one air neighbor");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void stackedSourcesUseSnapshotAndShaftDeletesLowerSource(GameTestHelper helper) {
        BlockPos upper = new BlockPos(8, 75, 0);
        BlockPos lower = new BlockPos(8, 70, 0);
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(upper, Blocks.LAVA.defaultBlockState());
        states.put(lower, Blocks.LAVA.defaultBlockState());
        states.put(upper.east(), Blocks.AIR.defaultBlockState());
        states.put(lower.west(), Blocks.AIR.defaultBlockState());
        ProtoChunk chunk = proto(helper, 0, 0);
        states.forEach((pos, state) -> chunk.setBlockState(pos, state, false));
        BlockPos chest = new BlockPos(8, 68, 0);
        chunk.setBlockState(chest, Blocks.CHEST.defaultBlockState(), false);
        chunk.setBlockEntity(new ChestBlockEntity(chest, Blocks.CHEST.defaultBlockState()));
        CorridorLavaPlan plan = CorridorLavaSources.plan(new Fixture(states), List.of(upper, lower));
        apply(chunk, plan);
        helper.assertTrue(chunk.getBlockState(upper).is(Blocks.LAVA), "upper source itself survives");
        for (int y = 66; y < 75; y++) {
            helper.assertTrue(chunk.getBlockState(new BlockPos(8, y, 0)).isAir(), "entire shaft including lower source " + y);
        }
        helper.assertTrue(chunk.getBlockState(lower.west()).is(Blocks.RED_STAINED_GLASS), "lower source was judged before deletion");
        helper.assertTrue(chunk.getBlockEntity(chest) == null, "cleared proto chest leaves no block entity");
        helper.assertTrue(CorridorLavaOriginals.state(chunk, lower).is(Blocks.LAVA), "original lower source remains available for later decisions");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void flowAndOutOfRangeSourcesDoNothing(GameTestHelper helper) {
        for (BlockPos source : List.of(new BlockPos(8, 66, 0), new BlockPos(8, 70, 65), new BlockPos(8, 70, -65))) {
            Fixture original = new Fixture(Map.of(source, Blocks.LAVA.defaultBlockState(),
                    source.east(), Blocks.AIR.defaultBlockState()));
            int[] writes = {0};
            CorridorLavaPlan plan = CorridorLavaSources.plan(original, List.of(source));
            plan.forEachClear(cell -> writes[0]++);
            plan.forEachGlass(cell -> writes[0]++);
            helper.assertTrue(writes[0] == 0, "out of source range " + source);
        }
        BlockPos source = new BlockPos(8, 70, 0);
        Fixture flowing = new Fixture(Map.of(source, Fluids.FLOWING_LAVA.getFlowing(7, false).createLegacyBlock(),
                source.east(), Blocks.AIR.defaultBlockState()));
        helper.assertTrue(!CorridorLavaSources.plan(flowing, List.of(source))
                .clears(new CorridorLavaPlan.Cell(8, 66, 0)), "flowing lava is not a source");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void stableNeighborCompletionOrdersKeepShaftsAboveGlass(GameTestHelper helper) {
        Map<BlockPos, BlockState> westFirst = completeNeighbors(helper, true);
        Map<BlockPos, BlockState> eastFirst = completeNeighbors(helper, false);
        helper.assertTrue(westFirst.equals(eastFirst), "stable final results must not depend on the neighbor completion order");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void lateEdgeSourceIsSeenAtStableFinalization(GameTestHelper helper) {
        Map<Long, ChunkAccess> chunks = stableChunks(helper, 0, 2);
        BlockPos source = new BlockPos(16, 90, 32);
        BlockPos westAir = source.west();
        sealedSource(chunks, source);
        put(chunks, source, Blocks.AIR.defaultBlockState());
        put(chunks, westAir, Blocks.AIR.defaultBlockState());
        // C2 writes into C1 before the barrier. No earlier lava snapshot/request exists.
        put(chunks, source, Blocks.LAVA.defaultBlockState());
        finish(chunks, 0, 2);
        helper.assertTrue(chunk(chunks, westAir).getBlockState(westAir).is(Blocks.RED_STAINED_GLASS), "stable source halo produces glass in C0");
        helper.assertTrue(chunk(chunks, source).getBlockState(source).is(Blocks.LAVA), "finishing C0 must not write the neighbor's blocks");
        finish(chunks, 1, 2);
        helper.assertTrue(chunk(chunks, source).getBlockState(source.below()).isAir(), "source owner applies its own shaft at finalization");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void naturalBasaltSealingLastOutletCreatesNoHistoricalClear(GameTestHelper helper) {
        Map<Long, ChunkAccess> chunks = stableChunks(helper, 0, 2);
        BlockPos source = new BlockPos(15, 90, 32);
        sealedSource(chunks, source);
        put(chunks, source.below(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(CorridorLavaSources.canFlowOut(new ProtoView(chunks), source), "fixture initially has only a down outlet");
        // BasaltPillarFeature.placeHangOff: the east neighbor's feature writes west
        // onto S.below() before the final FEATURES barrier, sealing the last outlet.
        put(chunks, source.below(), Blocks.BASALT.defaultBlockState());
        finish(chunks, 0, 2);
        finish(chunks, 1, 2);
        helper.assertTrue(chunk(chunks, source).getBlockState(source).is(Blocks.LAVA), "enclosed original source remains");
        helper.assertTrue(chunk(chunks, source).getBlockState(source.below()).is(Blocks.BASALT), "natural basalt seal must survive");
        helper.assertTrue(chunk(chunks, source).getBlockState(source.below(2)).is(Blocks.STONE), "no obsolete shaft may clear below the sealed source");
        helper.assertTrue(chunks.values().stream().allMatch(c -> CorridorLavaOriginals.outputs(c).isEmpty()), "sealed final source creates no AIR or glass requests");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void naturalFeatureReplacingSourceCreatesNoHistoricalOutputs(GameTestHelper helper) {
        Map<Long, ChunkAccess> chunks = stableChunks(helper, 0, 2);
        BlockPos source = new BlockPos(15, 90, 32);
        sealedSource(chunks, source);
        put(chunks, source.below(), Blocks.AIR.defaultBlockState());
        put(chunks, source.east(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(CorridorLavaSources.canFlowOut(new ProtoView(chunks), source), "fixture source originally could flow");
        // A later natural feature replaces the source before any lava transaction.
        put(chunks, source, Blocks.BASALT.defaultBlockState());
        finish(chunks, 1, 2);
        finish(chunks, 0, 2);
        helper.assertTrue(chunk(chunks, source).getBlockState(source).is(Blocks.BASALT), "replacement basalt remains");
        helper.assertTrue(chunk(chunks, source).getBlockState(source.below(2)).is(Blocks.STONE), "removed source cannot leave a shaft request");
        helper.assertTrue(chunk(chunks, source.east()).getBlockState(source.east()).isAir(), "removed source cannot leave edge glass");
        helper.assertTrue(chunks.values().stream().allMatch(c -> CorridorLavaOriginals.outputs(c).isEmpty()), "removed final source creates no historical outputs");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void generationBarrierCoversLateWritersWithoutStageCycle(GameTestHelper helper) {
        var generation = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.INITIALIZE_LIGHT);
        helper.assertTrue(generation.directDependencies().get(2) == ChunkStatus.FEATURES, "C2 FEATURES precedes C0 finalization");
        helper.assertTrue(generation.blockStateWriteRadius() == 0, "final block writes belong only to the center");
        for (int radius = 0; radius < generation.directDependencies().size(); radius++) {
            helper.assertTrue(generation.directDependencies().get(radius).isBefore(ChunkStatus.INITIALIZE_LIGHT),
                    "dependencies strictly precede the finalized stage, radius " + radius);
        }
        var loading = ChunkPyramid.LOADING_PYRAMID.getStepTo(ChunkStatus.INITIALIZE_LIGHT);
        helper.assertTrue(loading.directDependencies().size() == 1, "loading pyramid remains radius zero");
        helper.assertTrue(loading.blockStateWriteRadius() == -1, "loading writes remain disabled");
        helper.assertTrue(ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)
                .getAccumulatedRadiusOf(ChunkStatus.FEATURES) == 3, "documented global FEATURES scheduling radius");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void protoSerializationRetainsOriginalSourceAndPendingOperations(GameTestHelper helper) {
        ProtoChunk before = proto(helper, 0, 0);
        BlockPos source = new BlockPos(8, 70, 0);
        BlockPos glass = source.east();
        before.setBlockState(source, Blocks.LAVA.defaultBlockState(), false);
        CorridorLavaSources.applyToNewProto(before, source, Blocks.AIR.defaultBlockState());
        CorridorLavaSources.applyToNewProto(before, glass, Blocks.RED_STAINED_GLASS.defaultBlockState());
        CorridorLavaOriginals.markFinished(before);
        // Exercise the same serializer entry points used by ChunkSerializer, without disk I/O.
        var saved = before.writeAttachmentsToNBT(helper.getLevel().registryAccess());
        helper.assertTrue(saved != null, "generation provenance is serialized");
        ProtoChunk after = proto(helper, 0, 0);
        after.readAttachmentsFromNBT(helper.getLevel().registryAccess(), saved);
        helper.assertTrue(CorridorLavaOriginals.state(after, source).is(Blocks.LAVA), "deleted original source survives serialization");
        helper.assertTrue(CorridorLavaOriginals.sources(after).contains(source), "source index is recovered");
        helper.assertTrue(CorridorLavaOriginals.outputs(after).get(source).isAir(), "clear request is recovered");
        helper.assertTrue(CorridorLavaOriginals.outputs(after).get(glass).is(Blocks.RED_STAINED_GLASS), "edge glass request is recovered");
        helper.assertTrue(CorridorLavaOriginals.finished(after), "completion marker survives serialization");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend", batch = BATCH, timeoutTicks = 40)
    public static void liveChunksAndImposterWrappersAreNeverWritten(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var live = helper.getLevel().getChunkAt(pos);
        BlockState before = live.getBlockState(pos);
        for (var target : List.of(live, new ImposterProtoChunk(live, true), new ImposterProtoChunk(live, false))) {
            helper.assertTrue(!CorridorLavaSources.isNewProto(target), "FULL and wrappers are excluded");
            CorridorLavaSources.applyToNewProto(target, pos, Blocks.RED_STAINED_GLASS.defaultBlockState());
            helper.assertTrue(live.getBlockState(pos) == before, "old FULL terrain unchanged");
        }
        helper.succeed();
    }

    private static ProtoChunk proto(GameTestHelper helper, int x, int z) {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        return chunk;
    }

    private static void apply(ProtoChunk chunk, CorridorLavaPlan plan) {
        plan.forEachClear(cell -> CorridorLavaSources.applyToNewProto(chunk,
                new BlockPos(cell.x(), cell.y(), cell.z()), Blocks.AIR.defaultBlockState()));
        plan.forEachGlass(cell -> CorridorLavaSources.applyToNewProto(chunk,
                new BlockPos(cell.x(), cell.y(), cell.z()), Blocks.RED_STAINED_GLASS.defaultBlockState()));
    }

    private static Map<Long, ChunkAccess> stableChunks(GameTestHelper helper, int cx, int cz) {
        Map<Long, ChunkAccess> chunks = new HashMap<>();
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                ProtoChunk chunk = proto(helper, x, z);
                chunk.setPersistedStatus(ChunkStatus.FEATURES);
                chunks.put(chunk.getPos().toLong(), chunk);
            }
        }
        return chunks;
    }

    private static ChunkAccess chunk(Map<Long, ChunkAccess> chunks, BlockPos pos) {
        return chunks.get(new ChunkPos(pos).toLong());
    }

    private static void put(Map<Long, ChunkAccess> chunks, BlockPos pos, BlockState state) {
        chunk(chunks, pos).setBlockState(pos, state, false);
    }

    private static void sealedSource(Map<Long, ChunkAccess> chunks, BlockPos source) {
        for (int y = CorridorLavaPlan.CLEAR_FLOOR_Y; y < source.getY(); y++) {
            put(chunks, new BlockPos(source.getX(), y, source.getZ()), Blocks.STONE.defaultBlockState());
        }
        for (Direction direction : Direction.values()) put(chunks, source.relative(direction), Blocks.STONE.defaultBlockState());
        put(chunks, source, Blocks.LAVA.defaultBlockState());
    }

    private static void finish(Map<Long, ChunkAccess> chunks, int cx, int cz) {
        CorridorLavaSources.finishStableChunks(chunks.get(ChunkPos.asLong(cx, cz)), chunks, x -> true, x -> false);
    }

    private static Map<BlockPos, BlockState> completeNeighbors(GameTestHelper helper, boolean westFirst) {
        Map<Long, ChunkAccess> chunks = stableChunks(helper, 0, 2);
        BlockPos upper = new BlockPos(16, 95, 32);
        BlockPos lower = new BlockPos(16, 80, 32);
        BlockPos westSource = new BlockPos(15, 90, 32);
        BlockPos sideSource = new BlockPos(16, 85, 35);
        sealedSource(chunks, upper);
        sealedSource(chunks, lower);
        sealedSource(chunks, westSource);
        sealedSource(chunks, sideSource);
        put(chunks, upper.east(), Blocks.AIR.defaultBlockState());
        put(chunks, lower.east(), Blocks.AIR.defaultBlockState());
        put(chunks, westSource.east(), Blocks.AIR.defaultBlockState());
        put(chunks, sideSource.west(), Blocks.AIR.defaultBlockState());
        int first = westFirst ? 0 : 1;
        int second = westFirst ? 1 : 0;
        finish(chunks, first, 2);
        // Model the first owner becoming FULL before its neighbor finalizes.
        ((ProtoChunk) chunks.get(ChunkPos.asLong(first, 2))).setPersistedStatus(ChunkStatus.FULL);
        finish(chunks, second, 2);
        helper.assertTrue(chunk(chunks, upper).getBlockState(upper).is(Blocks.LAVA), "upper source remains");
        for (int y = 66; y < upper.getY(); y++) {
            helper.assertTrue(chunk(chunks, upper).getBlockState(new BlockPos(16, y, 32)).isAir(), "upper shaft clears lower source and overlapping edge glass at y=" + y);
        }
        helper.assertTrue(chunk(chunks, lower.east()).getBlockState(lower.east()).is(Blocks.RED_STAINED_GLASS), "lower source was evaluated before deletion");
        helper.assertTrue(CorridorLavaOriginals.state(chunk(chunks, lower), lower).is(Blocks.LAVA), "snapshot restores only our deleted lower source");
        helper.assertTrue(chunk(chunks, sideSource.west()).getBlockState(sideSource.west()).is(Blocks.RED_STAINED_GLASS), "cross-edge glass survives even when its source owner reached FULL first");
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = 14; x <= 18; x++) {
            for (int z = 31; z <= 36; z++) {
                for (int y = 65; y <= 96; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    result.put(pos, chunk(chunks, pos).getBlockState(pos));
                }
            }
        }
        return result;
    }

    private record ProtoView(Map<Long, ChunkAccess> chunks) implements BlockGetter {
        @Override public BlockState getBlockState(BlockPos pos) {
            var target = chunk(chunks, pos);
            return target == null ? Blocks.BEDROCK.defaultBlockState() : target.getBlockState(pos);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
    }

    /** Logical world coordinates keep the fixed source bounds independent of test plot layout. */
    private record Fixture(Map<BlockPos, BlockState> states) implements BlockGetter {
        Fixture {
            states = Map.copyOf(states);
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.STONE.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
    }
}
