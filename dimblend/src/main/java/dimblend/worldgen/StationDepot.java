package dimblend.worldgen;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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
     * Writes this chunk's share of every depot it touches. Called both from {@code FEATURES}
     * decoration and again at {@code INITIALIZE_LIGHT} head after the entire 3x3 {@code FEATURES}
     * neighborhood has finished so neighbor features can never leave biome decorations behind.
     */
    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        place(level.registryAccess(), rotating, chunk);
    }

    /**
     * RegistryAccess-backed entry point usable during chunk steps before or outside a
     * {@link net.minecraft.world.level.WorldGenLevel} wrapper (specifically at {@code INITIALIZE_LIGHT} head).
     */
    public static void place(RegistryAccess access, RotatingChunkGenerator rotating, ChunkAccess chunk) {
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
            writeChunkShare(access, chunk, boundary, rotating.hasStarterDepot(), minX, maxX, minZ, maxZ);
        }
    }

    /**
     * Re-applies {@link #place} on already-loaded 3x3 neighbor chunks after this chunk's
     * {@code FEATURES} pass finishes, matching {@link OakTrackCorridor#placeLoadedVaultNeighbors}
     * across the wider {@code |z| <= 15} depot footprint.
     */
    public static void placeLoadedDepotNeighbors(WorldGenLevel level, ChunkPos origin) {
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        RegistryAccess access = level.registryAccess();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = origin.x + dx;
                int nz = origin.z + dz;
                int minZ = nz * 16;
                int maxZ = minZ + 15;
                if (!StationDepotGeometry.depotIntersectsChunkZ(minZ, maxZ) || !level.hasChunk(nx, nz)) {
                    continue;
                }
                place(access, rotating, level.getChunk(nx, nz));
            }
        }
    }

    /**
     * True when a tree/fungus-like feature origin falls inside any depot's carved footprint
     * ({@code |z| <= 15}) where post-decoration carving would sever its trunk.
     */
    public static boolean blocksTreeLikeOrigin(WorldGenLevel level, BlockPos origin) {
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return false;
        }
        return StationDepotGeometry.blocksTreeLikeOrigin(
                origin.getX(),
                origin.getY() - OakTrackCorridor.TRACK_Y,
                origin.getZ(),
                rotating.bandSize(),
                region -> layout.laneName(layout.delegate(region)));
    }

    private static void writeChunkShare(
            RegistryAccess access,
            ChunkAccess chunk,
            int boundaryX,
            boolean starterDepot,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        Block lantern = block(access, ResourceLocation.withDefaultNamespace("lantern"));
        BlockState dryTrack = dryTrackState(block(access, CorridorTrackProfile.SURFACE.trackId()));

        int carveFromX = Math.max(minX, StationDepotGeometry.minBlockX(boundaryX));
        int carveToX = Math.min(maxX, StationDepotGeometry.maxBlockX(boundaryX));
        int carveFromZ = Math.max(minZ, -StationDepotGeometry.PLATFORM_HALF_WIDTH);
        int carveToZ = Math.min(maxZ, StationDepotGeometry.PLATFORM_HALF_WIDTH);
        for (int x = carveFromX; x <= carveToX; x++) {
            for (int z = carveFromZ; z <= carveToZ; z++) {
                clearColumn(chunk, dryTrack, starterDepot, x, z);
            }
        }
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
                case TRACK_XO -> dryTrack;
                case LANTERN -> lantern.defaultBlockState();
            };
            setDepotCell(chunk, cursor, cell.x(), y, cell.z(), state);
        }
    }

    /**
     * Empties the depot's chamber for one column so terrain and features cannot
     * leave blocks or orphan block entities floating inside it. Also removes unsupported
     * upward-growing stalks immediately above the carved ceiling.
     */
    private static void clearColumn(
            ChunkAccess chunk,
            BlockState dryTrack,
            boolean starterDepot,
            int x,
            int z
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = StationDepotGeometry.DECK_DY; dy <= StationDepotGeometry.CAVERN_APEX_DY; dy++) {
            int y = OakTrackCorridor.TRACK_Y + dy;
            if (starterDepot && StarterDepot.ownsCell(x, y, z)) {
                continue;
            }
            if (dy == 0 && z == OakTrackCorridor.CORRIDOR_Z) {
                if (starterDepot && StarterDepot.removesTrack(x)) {
                    setDepotCell(chunk, cursor, x, y, z, Blocks.AIR.defaultBlockState());
                } else {
                    ensureDryMainLineTrack(chunk, cursor, x, y, z, dryTrack);
                }
                continue;
            }
            BlockState current = chunk.getBlockState(cursor.set(x, y, z));
            if (current.is(Blocks.BEDROCK)) {
                continue;
            }
            setDepotCell(chunk, cursor, x, y, z, Blocks.AIR.defaultBlockState());
        }
        int ceilingStartY = OakTrackCorridor.TRACK_Y + StationDepotGeometry.CAVERN_APEX_DY + 1;
        int maxY = chunk.getMaxBuildHeight() - 1;
        for (int y = ceilingStartY; y <= maxY; y++) {
            BlockState above = chunk.getBlockState(cursor.set(x, y, z));
            if (!isUpwardSupportedFlora(above)) {
                break;
            }
            setDepotCell(chunk, cursor, x, y, z, Blocks.AIR.defaultBlockState());
        }
    }

    private static void ensureDryMainLineTrack(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            BlockState dryTrack
    ) {
        BlockState current = chunk.getBlockState(cursor.set(x, y, z));
        if (current.is(Blocks.BEDROCK)) {
            return;
        }
        if (current.getBlock() == dryTrack.getBlock()) {
            if (current.hasProperty(TrackBlock.WATERLOGGED) && current.getValue(TrackBlock.WATERLOGGED)) {
                setDepotCell(chunk, cursor, x, y, z, current.setValue(TrackBlock.WATERLOGGED, false));
            }
            return;
        }
        setDepotCell(chunk, cursor, x, y, z, dryTrack);
    }

    private static BlockState dryTrackState(Block track) {
        BlockState state = track.defaultBlockState()
                .setValue(TrackBlock.SHAPE, TrackShape.XO)
                .setValue(TrackBlock.HAS_BE, false);
        if (state.hasProperty(TrackBlock.WATERLOGGED)) {
            state = state.setValue(TrackBlock.WATERLOGGED, false);
        }
        return state;
    }

    private static void setDepotCell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            BlockState next
    ) {
        cursor.set(x, y, z);
        BlockState current = chunk.getBlockState(cursor);
        if (current.hasBlockEntity() || chunk.getBlockEntityNbt(cursor) != null) {
            chunk.removeBlockEntity(cursor.immutable());
        }
        chunk.setBlockState(cursor, next, false);
    }

    private static boolean isUpwardSupportedFlora(BlockState state) {
        return state.is(Blocks.BAMBOO)
                || state.is(Blocks.BAMBOO_SAPLING)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.BIG_DRIPLEAF)
                || state.is(Blocks.BIG_DRIPLEAF_STEM)
                || state.is(Blocks.SMALL_DRIPLEAF);
    }

    private static Block block(RegistryAccess access, ResourceLocation id) {
        Block block = access.registryOrThrow(Registries.BLOCK).get(id);
        if (block == null) {
            throw new IllegalStateException("missing required block " + id);
        }
        return block;
    }
}