package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Region partition wall + warp gate at band boundaries.
 *
 * Every region boundary column (block X = k * band_size) that separates two different
 * {@link BandLayout#laneName} identities gets a full-height wall of Biomes O' Plenty's
 * null block. Inside the railway corridor cross-section the wall yields to warp gate
 * blocks, which replace the carved vault, the track, and the roadbed. The gate is a
 * wall for unauthorized entities ({@link dimblend.block.WarpGateBlock#getCollisionShape})
 * and air for Sable's world mesh / Create carriages; {@link WarpGatePassageGuard} is
 * only the clip/teleport safety net.
 *
 * Boundaries whose adjacent regions share a lane name (see
 * {@link BandLayout#sameLaneAcrossBoundary}) get nothing — those stay open corridors
 * and skip {@link RegionBoundaryNoStructureZone}. Walled boundaries get that zone
 * on both sides so structures cannot grow into the wall.
 *
 * Ownership rule: {@link OakTrackCorridor} never touches boundary columns (see the skip in
 * its carve loop), so neighbor re-carves during decoration cannot wipe the gate after this
 * pass wrote it. This class exclusively owns boundary columns.
 *
 * The wall cube itself is BOP null_block (normal hardness, piston-normal). Survival
 * mining / explosions / vanilla pistons are blocked by {@link RegionBoundaryWallProtector};
 * warp-gate cells already use {@code strength(-1, 3600000)} and {@code PushReaction.BLOCK}.
 * BOP End Corruption also places this block as tree trunks in End bands — protection is
 * column-scoped so those trunks stay mineable.
 */
public final class RegionBoundaryWall {
    public static final ResourceLocation WALL_ID =
            ResourceLocation.fromNamespaceAndPath("biomesoplenty", "null_block");

    private RegionBoundaryWall() {
    }

    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        Block wallBlock = level.registryAccess().registryOrThrow(Registries.BLOCK).get(WALL_ID);
        if (wallBlock == null) {
            throw new IllegalStateException("missing required block " + WALL_ID);
        }
        BlockState wall = wallBlock.defaultBlockState();
        // Feature overhang (leaves, snow, fluids, trees) spills from any neighbor into a
        // boundary column after this chunk already wrote it. Mirror
        // OakTrackCorridor.placeLoadedVaultNeighbors: rewrite every loaded 3x3 chunk whose
        // west edge is a wall-owned boundary, so whichever side decorates last leaves the
        // column in its final wall/gate state. The previous east-only rewrite missed
        // north/south (and diagonal) spills along the same wall X.
        ChunkPos origin = chunk.getPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkAccess target;
                if (dx == 0 && dz == 0) {
                    target = chunk;
                } else if (level.hasChunk(origin.x + dx, origin.z + dz)) {
                    target = level.getChunk(origin.x + dx, origin.z + dz);
                } else {
                    continue;
                }
                int wallX = target.getPos().getMinBlockX();
                if (ownsBoundaryColumn(rotating, wallX)) {
                    writeColumn(target, rotating, wallX, wall);
                }
            }
        }
    }

    /**
     * True when this block column is a boundary owned by the wall/gate, i.e. a region
     * boundary whose two sides differ in {@link BandLayout#laneName}. Shared by worldgen
     * and {@link WarpGatePassageGuard} so both always agree on where gates exist.
     */
    public static boolean ownsBoundaryColumn(RotatingChunkGenerator rotating, int blockX) {
        return ownsBoundaryColumn(rotating.layout(), rotating.bandSize(), blockX);
    }

    public static boolean ownsBoundaryColumn(BandLayout layout, int bandSize, int blockX) {
        if (Math.floorMod(blockX, bandSize) != 0) {
            return false;
        }
        return !layout.sameLaneAcrossBoundary(blockX, bandSize);
    }

    /**
     * Runtime gate check for the passage guard: returns the block X of the first
     * boundary-column slab the given box overlaps, or -1. Cells outside the octagon shape
     * are solid wall, so blocking the whole bounding box only ever affects entities
     * already inside wall/gate blocks.
     */
    public static int overlappedBoundaryColumn(
            RotatingChunkGenerator rotating,
            double minX, double maxX,
            double minY, double maxY,
            double minZ, double maxZ) {
        int bandSize = rotating.bandSize();
        int from = (int) Math.floor(minX);
        int to = (int) Math.floor(maxX - 1.0E-7);
        for (int x = from; x <= to; x++) {
            if (Math.floorMod(x, bandSize) != 0 || !ownsBoundaryColumn(rotating, x)) {
                continue;
            }
            if (x + 1 > minX && x < maxX
                    && maxY >= OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_FLOOR_DY
                    && minY <= OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_APEX_DY
                    && maxZ >= OakTrackCorridor.CORRIDOR_Z - OakTrackCorridor.VAULT_RADIUS
                    && minZ <= OakTrackCorridor.CORRIDOR_Z + OakTrackCorridor.VAULT_RADIUS + 1) {
                return x;
            }
        }
        return -1;
    }

    private static void writeColumn(ChunkAccess chunk, RotatingChunkGenerator rotating, int wallX, BlockState wall) {
        int maxDy = OakTrackCorridor.vaultMaxDy(rotating, wallX);
        BlockState gate = DimBlendRegistries.WARP_GATE.get().defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = chunk.getPos().getMaxBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        for (int z = minZ; z <= maxZ; z++) {
            int dz = z - OakTrackCorridor.CORRIDOR_Z;
            for (int y = minY; y < maxY; y++) {
                boolean gateCell = isGateCell(dz, y - OakTrackCorridor.TRACK_Y, maxDy);
                cursor.set(wallX, y, z);
                chunk.setBlockState(cursor, gateCell ? gate : wall, false);
                if (gateCell) {
                    // ProtoChunk.setBlockState never creates block entities — the vanilla
                    // gateway feature gets its BE from a follow-up getBlockEntity call,
                    // which we skip. Without this the gate cell has no BE, the server
                    // never syncs one, and the cell renders as a see-through hole.
                    chunk.setBlockEntity(DimBlendRegistries.WARP_GATE.get().newBlockEntity(cursor.immutable(), gate));
                }
            }
        }
    }

    /**
     * Gate cells: the carved vault octagon (including the roadbed row at dy = -1, which
     * {@link OakTrackCorridor#inVault} already covers at half-width 3) up to the per-band
     * carve ceiling. When the corridor does not carve here (maxDy < 0) there is no gate —
     * the plain wall runs the full column.
     */
    private static boolean isGateCell(int dz, int dy, int maxDy) {
        return maxDy >= 0
                && dy >= OakTrackCorridor.VAULT_FLOOR_DY
                && dy <= maxDy
                && OakTrackCorridor.inVault(dz, dy);
    }
}
