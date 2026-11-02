package dimblend.worldgen;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Writes the R6 station depot ("起始站场") at every surface/surface region boundary
 * this chunk touches — the blocks {@link StationDepotGeometry} describes, and
 * nothing that needs a block entity.
 *
 * <p><b>Worker threads only.</b> Everything here goes through
 * {@link ChunkAccess#setBlockState} without a level update and only into the chunk
 * being decorated. Reading a neighbour is never needed: the layout is a pure
 * function of the boundary column, so a chunk needs no information from outside
 * itself. That keeps the pass clear of the sync chunk loads and the 15–35 s
 * server-thread stalls this corridor has hit before.
 *
 * <p><b>Everything is clipped to this chunk first.</b> {@code setBlockState} does not
 * reject a coordinate outside the chunk: it wraps it with {@code & 15} and writes into
 * an unrelated column of the same chunk. A depot is 76 blocks wide and 33 deep, so
 * chunks far from the boundary column still reach this pass; without the clip they
 * stamp a wrapped copy of the deck onto every Z chunk, and a chunk on the depot's west
 * edge (which contains no boundary column at all) is skipped entirely. Every write
 * therefore goes through {@link StationDepotGeometry#chunkContainsBlock} or is clipped
 * to {@code minX..maxX} / {@code minZ..maxZ}.
 *
 * <p><b>Ownership.</b> Boundary columns belong to {@link RegionBoundaryWall}, and the
 * depot only ever covers surface/surface boundaries — the ones that class never
 * walls. The chamber also stops 11 blocks short of the next boundary column, so
 * even a changed lane assignment cannot leave the depot touching a wall.
 *
 * <p><b>What is deliberately missing.</b> The two merge curves, the switch and the
 * two Sable chassis are not here. A curve and a switch are {@code TrackBlockEntity}
 * contents that must join Create's track graph on the server thread, and Sable
 * assembly cannot run on a worker thread with no players. {@link StationDepotFitOut}
 * adds those the first time a player comes within range.
 */
public final class StationDepot {
    private StationDepot() {
    }

    /**
     * Writes this chunk's share of every depot it touches. Called from the
     * decoration pass of the chunk itself, after the corridor and the boundary wall.
     */
    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return;
        }
        int bandSize = rotating.bandSize();
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int maxX = pos.getMaxBlockX();
        int minZ = pos.getMinBlockZ();
        int maxZ = pos.getMaxBlockZ();
        // The depot never leaves z = -16..16, so every other chunk row has nothing to do.
        if (!StationDepotGeometry.depotIntersectsChunkZ(minZ, maxZ)) {
            return;
        }
        for (int boundary : StationDepotGeometry.boundariesTouchingChunkX(bandSize, minX, maxX)) {
            if (!StationDepotGeometry.buildsDepot(boundary, bandSize, region -> layout.laneName(layout.delegate(region)))) {
                continue;
            }
            writeChunkShare(level, chunk, boundary, minX, maxX, minZ, maxZ);
        }
    }

    private static void writeChunkShare(
            WorldGenLevel level,
            ChunkAccess chunk,
            int boundaryX,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        Block workbench = block(level, ResourceLocation.withDefaultNamespace("crafting_table"));
        Block lantern = block(level, ResourceLocation.withDefaultNamespace("lantern"));
        Block track = block(level, CorridorTrackProfile.SURFACE.trackId());

        int carveFromX = Math.max(minX, StationDepotGeometry.minBlockX(boundaryX));
        int carveToX = Math.min(maxX, StationDepotGeometry.maxBlockX(boundaryX));
        int carveFromZ = Math.max(minZ, -StationDepotGeometry.PLATFORM_HALF_WIDTH);
        int carveToZ = Math.min(maxZ, StationDepotGeometry.PLATFORM_HALF_WIDTH);
        for (int x = carveFromX; x <= carveToX; x++) {
            for (int z = carveFromZ; z <= carveToZ; z++) {
                clearColumn(chunk, x, z);
            }
        }
        sealShell(chunk, boundaryX, minX, maxX, minZ, maxZ);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (StationDepotGeometry.Cell cell : StationDepotGeometry.placedCells(boundaryX)) {
            if (!StationDepotGeometry.chunkContainsBlock(cell.x(), cell.z(), minX, maxX, minZ, maxZ)) {
                continue;
            }
            int y = OakTrackCorridor.TRACK_Y + cell.dy();
            BlockState state = switch (cell.kind()) {
                case COBBLE -> cobble;
                case BRICK -> brick;
                case PILLAR -> brick;
                case BUFFER -> brick;
                case TRACK_XO -> track.defaultBlockState()
                        .setValue(TrackBlock.SHAPE, TrackShape.XO)
                        .setValue(TrackBlock.HAS_BE, false);
                case WORKBENCH -> workbench.defaultBlockState();
                case LANTERN -> lantern.defaultBlockState();
            };
            chunk.setBlockState(cursor.set(cell.x(), y, cell.z()), state, false);
        }
    }

    /**
     * Empties the depot's chamber for one column so terrain and features cannot
     * leave blocks floating inside it. The deck layer is filled by the cell list;
     * here everything from the ceiling down to one below the deck becomes air.
     */
    private static void clearColumn(ChunkAccess chunk, int x, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = StationDepotGeometry.DECK_DY; dy <= StationDepotGeometry.CAVERN_APEX_DY; dy++) {
            int y = OakTrackCorridor.TRACK_Y + dy;
            if (dy == 0 && z == OakTrackCorridor.CORRIDOR_Z) {
                // The main line's rail is owned by OakTrackCorridor and the fit-out.
                continue;
            }
            BlockState current = chunk.getBlockState(cursor.set(x, y, z));
            if (current.is(Blocks.BEDROCK)) {
                continue;
            }
            chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
        }
    }

    /**
     * Walls and roofs the hall. The depot is wider than the corridor's own 19-wide
     * tunnel, so without this ring, water and lava in the surrounding terrain flow
     * through the new side faces. Existing terrain is kept where it is already solid,
     * so the hall reads as a hall carved out of the ground rather than a stone box.
     */
    private static void sealShell(ChunkAccess chunk, int boundaryX, int minX, int maxX, int minZ, int maxZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState stone = Blocks.STONE.defaultBlockState();
        int from = Math.max(minX, StationDepotGeometry.writeMinBlockX(boundaryX));
        int to = Math.min(maxX, StationDepotGeometry.writeMaxBlockX(boundaryX));
        int fromZ = Math.max(minZ, StationDepotGeometry.writeMinBlockZ());
        int toZ = Math.min(maxZ, StationDepotGeometry.writeMaxBlockZ());
        for (int x = from; x <= to; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int dy = StationDepotGeometry.DECK_DY - 1;
                        dy <= StationDepotGeometry.CAVERN_APEX_DY + 1; dy++) {
                    if (!StationDepotGeometry.isShell(boundaryX, x, dy, z)) {
                        continue;
                    }
                    int y = OakTrackCorridor.TRACK_Y + dy;
                    BlockState current = chunk.getBlockState(cursor.set(x, y, z));
                    if (current.isAir() || isFluid(current)) {
                        chunk.setBlockState(cursor, stone, false);
                    }
                }
            }
        }
    }

    private static boolean isFluid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    private static Block block(WorldGenLevel level, ResourceLocation id) {
        Block block = level.registryAccess().registryOrThrow(Registries.BLOCK).get(id);
        if (block == null) {
            throw new IllegalStateException("missing required block " + id);
        }
        return block;
    }
}