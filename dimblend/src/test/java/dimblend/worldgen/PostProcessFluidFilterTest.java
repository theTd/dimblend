package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 纯逻辑单测：覆盖 {@link PostProcessFluidFilter#isWaterSpreadBlockedNeighbor}、
 * 主线程 {@link PostProcessFluidFilter#canSkipPostProcessTickAt} 以及 SPAWN 阶段
 * {@link PostProcessFluidFilter#prunePostProcessingLists}（含洞穴雕刻空腔、流动水邻格、
 * 区块边界列与含水方块的保守放行）。
 */
class PostProcessFluidFilterTest {
    @BeforeAll
    static void bootstrapItemIds() throws ReflectiveOperationException {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Field bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        bootstrapped.setBoolean(null, true);
        assertNotNull(Blocks.AIR, "原版方块表应已注册");
    }

    @Test
    void sourceWaterBlocksSpread() {
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.WATER.defaultBlockState()));
    }

    @Test
    void flowingWaterNeverBlocksSpread() {
        for (int level = 1; level <= 8; level++) {
            BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
            assertFalse(
                    PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(flowing),
                    "LEVEL=" + level + " 是流动水，水源仍须 tick 把它转成静水");
        }
    }

    @Test
    void solidTerrainBlocksSpread() {
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.STONE.defaultBlockState()));
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.DEEPSLATE.defaultBlockState()));
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.DIRT.defaultBlockState()));
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.SAND.defaultBlockState()));
    }

    @Test
    void airAndLavaAndStructureVoidDoNotBlockSpread() {
        assertFalse(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.AIR.defaultBlockState()));
        assertFalse(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.CAVE_AIR.defaultBlockState()));
        assertFalse(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.LAVA.defaultBlockState()));
        assertFalse(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(Blocks.STRUCTURE_VOID.defaultBlockState()));
    }

    @Test
    void waterloggableSlabsNeverBlockSpread() {
        BlockState dry = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, false);
        BlockState wet = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true);
        assertFalse(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(dry), "干台阶可以被灌水");
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(wet), "含水台阶已是水源");
    }

    @Test
    void doorBlocksSpreadInBothStates() {
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState();
        BlockState open = closed.setValue(DoorBlock.OPEN, true);
        assertTrue(closed.blocksMotion(), "关着的门有碰撞箱");
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(closed));
        assertTrue(open.blocksMotion(), "开着的门仍有门板碰撞箱");
        assertTrue(PostProcessFluidFilter.isWaterSpreadBlockedNeighbor(open));
    }

    @Test
    void mainThreadTickFilterSkipsOnlyEnclosedSourceWaterWithLoadedNeighbors() {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        Map<Long, BlockState> world = new HashMap<>();
        world.put(BlockPos.asLong(0, 50, 0), water);
        world.put(BlockPos.asLong(0, 49, 0), stone);
        world.put(BlockPos.asLong(-1, 50, 0), water);
        world.put(BlockPos.asLong(1, 50, 0), stone);
        world.put(BlockPos.asLong(0, 50, -1), water);
        world.put(BlockPos.asLong(0, 50, 1), water);

        PostProcessFluidFilter.BlockLookup lookup = (x, y, z) -> world.get(BlockPos.asLong(x, y, z));
        assertTrue(PostProcessFluidFilter.canSkipPostProcessTickAt(0, 50, 0, -64, water.getFluidState(), lookup),
                "enclosed source water (even on chunk border x=0 when neighbor x=-1 is loaded) must skip tick");

        // Missing cross-chunk neighbor (getChunkNow == null -> lookup returns null) must NOT skip
        world.remove(BlockPos.asLong(-1, 50, 0));
        assertFalse(PostProcessFluidFilter.canSkipPostProcessTickAt(0, 50, 0, -64, water.getFluidState(), lookup),
                "unloaded neighbor chunk must fall back to vanilla fluid tick");

        // World floor y == minBuildHeight must NOT skip
        world.put(BlockPos.asLong(-1, 50, 0), water);
        assertFalse(PostProcessFluidFilter.canSkipPostProcessTickAt(0, 50, 0, 50, water.getFluidState(), lookup));
    }

    @Test
    void workerThreadProtoMarkPruningKeepsCarvedAirBordersAndShapeCheckBlocks() {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState caveAir = Blocks.CAVE_AIR.defaultBlockState();
        BlockState wetSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true);

        Map<Long, BlockState> chunkBlocks = new HashMap<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                chunkBlocks.put(BlockPos.asLong(lx, 48, lz), stone);
                chunkBlocks.put(BlockPos.asLong(lx, 49, lz), water);
                chunkBlocks.put(BlockPos.asLong(lx, 50, lz), water);
            }
        }
        // Simulate applyCarvers replacing (6, 49, 5) with CAVE_AIR: neighbor (5, 49, 5) MUST stay marked!
        chunkBlocks.put(BlockPos.asLong(6, 49, 5), caveAir);
        // Simulate a waterlogged slab placed at (8, 49, 8): MUST stay marked for updateFromNeighbourShapes!
        chunkBlocks.put(BlockPos.asLong(8, 49, 8), wetSlab);

        short enclosedSeabed = ProtoChunk.packOffsetCoordinates(new BlockPos(3, 49, 3));
        short enclosedSurface = ProtoChunk.packOffsetCoordinates(new BlockPos(3, 50, 3));
        short nextToCarvedAir = ProtoChunk.packOffsetCoordinates(new BlockPos(5, 49, 5));
        short borderCell = ProtoChunk.packOffsetCoordinates(new BlockPos(0, 49, 5));
        short waterloggedBlock = ProtoChunk.packOffsetCoordinates(new BlockPos(8, 49, 8));

        // minSectionY = -4 (minBuildHeight = -64); y = 49..50 lives in sectionY = 3 -> sectionIndex = 7
        ShortList[] lists = new ShortList[24];
        lists[7] = new ShortArrayList(new short[] {
                enclosedSeabed,
                nextToCarvedAir,
                enclosedSurface,
                borderCell,
                waterloggedBlock
        });

        PostProcessFluidFilter.prunePostProcessingLists(
                lists,
                -4,
                -64,
                (lx, y, lz) -> chunkBlocks.getOrDefault(BlockPos.asLong(lx, y, lz), Blocks.AIR.defaultBlockState()));

        assertEquals(
                new ShortArrayList(new short[] {nextToCarvedAir, borderCell, waterloggedBlock}),
                lists[7],
                "enclosed interior source water must be pruned, while carved-air neighbors, chunk borders, and non-LiquidBlock waterlogged blocks remain marked");
    }
}
