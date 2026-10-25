package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.test.VanillaTestBootstrap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/**
 * Pure tests of the buried-block visibility search, without region files: the surface and the piece
 * boxes are supplied directly, exactly like {@link StructureShapeExtractor} supplies them from the
 * saved chunk heightmaps and the structure start.
 *
 * <p>Geometry convention: {@code H} is the first Y above a column's topmost non-air block, as the
 * saved {@code WORLD_SURFACE} heightmap stores it. A structure's own blocks therefore sit below the
 * {@code H} their chunk reports unless they stick out into an air column.
 */
class BuriedBlockFilterTest {
    static {
        VanillaTestBootstrap.ensure();
    }

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
    private static final long BUDGET = 16_777_216L;
    private static final BuriedBlockFilter.PieceMembership NO_PIECES = (x, y, z) -> false;

    @Test
    void aFullyBuriedBoxGivesAnEmptyResult() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, 10, 0, 2, 12, 2);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, (x, z) -> 20, boxMembership(0, 10, 0, 2, 12, 2), BUDGET, () -> false);

        assertTrue(result.blocks().isEmpty());
        assertFalse(result.volumeTooLarge());
        assertFalse(result.aborted());
    }

    @Test
    void aHouseWithARoofOverhangKeepsItsWallsButNotItsEnclosedFloor() {
        House house = new House(false, false);
        Long2ObjectOpenHashMap<BlockState> blocks = house.blocks();

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, house.surface(), house::isInsidePiece, BUDGET, () -> false);

        Set<BlockPos> kept = positions(result.blocks());
        assertTrue(kept.contains(new BlockPos(0, 64, 3)), "the wall under the overhang is one step from open air");
        assertTrue(kept.contains(new BlockPos(20, 65, 6)), "the far wall is reached the same way");
        assertTrue(kept.contains(new BlockPos(-1, 67, 3)), "the overhang itself has open sky above it");
        assertTrue(kept.contains(new BlockPos(10, 67, 3)), "the roof is seeded from above");
        for (BlockPos position : kept) {
            assertFalse(position.getY() == 63,
                    "the enclosed interior floor must not survive: " + position);
        }
    }

    @Test
    void anOpenDoorLetsTheSearchReachOnlyTheFloorWithinEightSteps() {
        House house = new House(true, false);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                house.blocks(), house.surface(), house::isInsidePiece, BUDGET, () -> false);

        Set<BlockPos> kept = positions(result.blocks());
        assertTrue(kept.contains(new BlockPos(0, 64, 3)), "the wall under the overhang still survives");
        assertTrue(kept.contains(new BlockPos(3, 63, 2)), "the floor just behind the door is reachable");
        assertTrue(kept.contains(new BlockPos(6, 63, 2)), "eight steps from the open column reach x = 6");
        assertFalse(kept.contains(new BlockPos(7, 63, 2)), "nine steps is past the search limit");
        assertFalse(kept.contains(new BlockPos(19, 63, 2)), "the far interior floor stays buried");
    }

    @Test
    void aNonOccludingDoorIsPassedThroughByTheSearch() {
        House house = new House(true, true);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                house.blocks(), house.surface(), house::isInsidePiece, BUDGET, () -> false);

        Set<BlockPos> kept = positions(result.blocks());
        assertTrue(kept.contains(new BlockPos(0, 64, 2)), "the leaves of the doorway are kept");
        assertTrue(kept.contains(new BlockPos(3, 63, 2)),
                "leaves do not occlude, so the search reaches the floor behind them");
    }

    @Test
    void aStructureUnderWaterGivesAnEmptyResult() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, 60, 0, 3, 63, 3);

        // Water is non-air, so WORLD_SURFACE is the water surface: all structure cells are below it.
        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, (x, z) -> 71, boxMembership(0, 60, 0, 3, 63, 3), BUDGET, () -> false);

        assertTrue(result.blocks().isEmpty());
    }

    @Test
    void anOpenRavineColumnExposesOnlyTheBlocksNextToIt() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, -40, 0, 9, -30, 9);
        // The ravine is the open column at x = 5: it contains no blocks.
        for (int y = -40; y <= -30; y++) {
            for (int z = 0; z <= 9; z++) {
                blocks.remove(BlockPos.asLong(5, y, z));
            }
        }

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, (x, z) -> x == 5 ? -60 : 64, boxMembership(0, -40, 0, 9, -30, 9),
                BUDGET, () -> false);

        Set<BlockPos> kept = positions(result.blocks());
        assertEquals(2 * 11 * 10, kept.size());
        for (int y = -40; y <= -30; y++) {
            for (int z = 0; z <= 9; z++) {
                assertTrue(kept.contains(new BlockPos(4, y, z)));
                assertTrue(kept.contains(new BlockPos(6, y, z)));
                assertFalse(kept.contains(new BlockPos(3, y, z)));
                assertFalse(kept.contains(new BlockPos(7, y, z)));
            }
        }
    }

    @Test
    void anOpenColumnJustOutsideTheVolumeStillExposesTheEdgeBlock() {
        Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        blocks.put(BlockPos.asLong(0, 10, 0), STONE);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks,
                (x, z) -> x <= -2 ? 0 : 20,
                boxMembership(-1, 10, 0, 0, 10, 0),
                BUDGET,
                () -> false);

        // The volume is x = -1..1; the open column at x = -2 seeds the passable cell x = -1, which
        // is the kept block's neighbour.
        assertEquals(1, result.blocks().size());
        assertTrue(result.blocks().containsKey(BlockPos.asLong(0, 10, 0)));
    }

    @Test
    void caveAirOutsideThePieceBoxesExposesNothing() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, -40, 0, 3, -30, 3);

        // The cave is below the surface, so it is not open sky and, outside the piece boxes, it is
        // not passable either: it must not seed or conduct the search.
        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, (x, z) -> 64, boxMembership(0, -40, 0, 3, -30, 3), BUDGET, () -> false);

        assertTrue(result.blocks().isEmpty());
    }

    @Test
    void anUnknownSurfaceClosesTheColumn() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, 0, 0, 2, 2, 2);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks,
                (x, z) -> ChunkHeightmaps.CLOSED,
                boxMembership(0, 0, 0, 2, 2, 2),
                BUDGET,
                () -> false);

        assertTrue(result.blocks().isEmpty());
    }

    @Test
    void aVolumeAboveTheBudgetIsRefusedInsteadOfReturningUnfilteredBlocks() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, 0, 0, 2, 2, 2);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, (x, z) -> 100, NO_PIECES, 10L, () -> false);

        assertTrue(result.volumeTooLarge());
        // Block bounds grown by one on every side: 5 x 5 x 5.
        assertEquals(125L, result.volumeCells());
        assertTrue(result.blocks().isEmpty());
    }

    @Test
    void cancellationStopsTheSearch() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, 0, 0, 2, 2, 2);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, (x, z) -> 0, NO_PIECES, BUDGET, () -> true);

        assertTrue(result.aborted());
    }

    @Test
    void openSkyAboveTheSurfaceKeepsEverySurfaceBlock() {
        Long2ObjectOpenHashMap<BlockState> blocks = box(STONE, 0, 0, 0, 2, 2, 2);

        BuriedBlockFilter.Result result = BuriedBlockFilter.filter(
                blocks, (x, z) -> 0, boxMembership(0, 0, 0, 2, 2, 2), BUDGET, () -> false);

        assertEquals(27, result.blocks().size());
    }

    private static Long2ObjectOpenHashMap<BlockState> box(
            BlockState state, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    blocks.put(BlockPos.asLong(x, y, z), state);
                }
            }
        }
        return blocks;
    }

    private static BuriedBlockFilter.PieceMembership boxMembership(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return (x, y, z) -> x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    private static Set<BlockPos> positions(Long2ObjectOpenHashMap<BlockState> blocks) {
        Set<BlockPos> positions = new HashSet<>();
        for (long position : blocks.keySet()) {
            positions.add(BlockPos.of(position));
        }
        return positions;
    }

    /**
     * A 21 x 7 house on ground at Y = 63. Walls at Y 64..66, roof at Y 67 with a one-block overhang on
     * every side, interior floor at Y 63. The saved surface is the ground outside the roof footprint
     * and the roof itself inside it, exactly what a chunk heightmap would report.
     */
    private static final class House {
        private static final int FLOOR_Y = 63;
        private static final int FLOOR_MAX_Y = 66;
        private static final int ROOF_Y = 67;
        private static final int MIN_X = 0;
        private static final int MAX_X = 20;
        private static final int MIN_Z = 0;
        private static final int MAX_Z = 6;
        private static final int ROOF_MIN_X = -1;
        private static final int ROOF_MAX_X = 21;
        private static final int ROOF_MIN_Z = -1;
        private static final int ROOF_MAX_Z = 7;
        private static final int DOOR_Z = 2;
        private static final int DOOR_X = 0;
        private static final int SURFACE_Y = 64;
        private static final int ROOF_SURFACE_Y = 68;

        private final boolean openDoor;
        private final boolean leavesDoor;

        private House(boolean openDoor, boolean leavesDoor) {
            this.openDoor = openDoor;
            this.leavesDoor = leavesDoor;
        }

        private Long2ObjectOpenHashMap<BlockState> blocks() {
            Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                for (int x = MIN_X; x <= MAX_X; x++) {
                    put(blocks, x, FLOOR_Y, z, STONE);
                }
            }
            for (int y = SURFACE_Y; y <= FLOOR_MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    putWall(blocks, MIN_X, y, z);
                    putWall(blocks, MAX_X, y, z);
                }
                for (int x = MIN_X + 1; x < MAX_X; x++) {
                    putWall(blocks, x, y, MIN_Z);
                    putWall(blocks, x, y, MAX_Z);
                }
            }
            for (int z = ROOF_MIN_Z; z <= ROOF_MAX_Z; z++) {
                for (int x = ROOF_MIN_X; x <= ROOF_MAX_X; x++) {
                    put(blocks, x, ROOF_Y, z, STONE);
                }
            }
            return blocks;
        }

        private void putWall(Long2ObjectOpenHashMap<BlockState> blocks, int x, int y, int z) {
            if (isDoor(x, y, z)) {
                if (this.leavesDoor) {
                    put(blocks, x, y, z, LEAVES);
                }
                return;
            }
            put(blocks, x, y, z, STONE);
        }

        private boolean isDoor(int x, int y, int z) {
            return this.openDoor && x == DOOR_X && z == DOOR_Z
                    && (y == SURFACE_Y || y == SURFACE_Y + 1);
        }

        private BuriedBlockFilter.ColumnSurface surface() {
            return (x, z) -> x >= ROOF_MIN_X && x <= ROOF_MAX_X && z >= ROOF_MIN_Z && z <= ROOF_MAX_Z
                    ? ROOF_SURFACE_Y
                    : SURFACE_Y;
        }

        private boolean isInsidePiece(int x, int y, int z) {
            return x >= ROOF_MIN_X && x <= ROOF_MAX_X
                    && y >= FLOOR_Y && y <= ROOF_Y
                    && z >= ROOF_MIN_Z && z <= ROOF_MAX_Z;
        }

        private static void put(
                Long2ObjectOpenHashMap<BlockState> blocks, int x, int y, int z, BlockState state) {
            blocks.put(BlockPos.asLong(x, y, z), state);
        }
    }
}
