package dimblend.worldgen;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;


public final class OakTrackCorridor {
    public static final int CORRIDOR_Z = 0;
    public static final int TRACK_Y = 64;
    /** Inclusive |dz| of the tunnel midsection. Width 19 = Z[-9, +9]. */
    public static final int VAULT_RADIUS = 9;
    /** Tunnel floor opens one block below the track; Y=62 below is solid floor. */
    public static final int VAULT_FLOOR_DY = -1;
    /** Flat ceiling row is this many blocks above the track. */
    public static final int VAULT_APEX_DY = 17;
    /** No structures may generate within this many chunks of CORRIDOR_Z (either side). */
    public static final int NO_STRUCTURE_CHUNK_RANGE = 16;
    public static final ResourceLocation TRACK_ID = ResourceLocation.fromNamespaceAndPath("railways", "track_create_andesite_wide");
    /** Half-width of the cobblestone roadbed under the track. 7 wide = Z[-3, +3]. */
    private static final int ROADBED_HALF_WIDTH = 3;
    /** How deep below the vault floor a tree origin may sit and still lose its support column. */
    private static final int TREE_UNDERMINED_DEPTH = 48;
    private static final Direction[] DIRECTIONS = Direction.values();

    private OakTrackCorridor() {
    }

    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        carveVault(level.getLevel().getChunkSource().getGenerator(), level.registryAccess(), chunk, null);
    }

    public static void reclearLoadedChunk(ServerLevel level, ChunkAccess chunk) {
        carveVault(level.getChunkSource().getGenerator(), level.registryAccess(), chunk, level);
    }

    private static void carveVault(
            ChunkGenerator generator,
            RegistryAccess access,
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live
    ) {
        int chunkZ = chunk.getPos().z;
        if (!touchesVault(chunkZ)) {
            return;
        }
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        Block trackBlock = access.registryOrThrow(Registries.BLOCK).get(TRACK_ID);
        if (trackBlock == null) {
            throw new IllegalStateException("missing required block railways:track_create_andesite_wide");
        }

        int minX = chunk.getPos().getMinBlockX();
        int maxX = chunk.getPos().getMaxBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = chunk.getPos().getMaxBlockZ();
        int vaultMinZ = Math.max(minZ, CORRIDOR_Z - VAULT_RADIUS);
        int vaultMaxZ = Math.min(maxZ, CORRIDOR_Z + VAULT_RADIUS);
        if (vaultMinZ > vaultMaxZ) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean[] carved = new boolean[maxX - minX + 1];
        for (int x = minX; x <= maxX; x++) {
            ChunkGenerator delegate = corridorDelegate(rotating, x);
            boolean carveBedrock = BandLayout.isVoidscape(delegate);
            if (chunkZ == 0) {
                writeTrack(chunk, cursor, x, trackBlock, carveBedrock, live);
            }
            int maxDy = vaultMaxDy(delegate);
            if (maxDy < 0) {
                continue;
            }
            carved[x - minX] = true;
            clearVaultColumn(chunk, cursor, x, vaultMinZ, vaultMaxZ, trackBlock, maxDy, carveBedrock, live);
            stabilizeVaultCeiling(chunk, cursor, x, vaultMinZ, vaultMaxZ, maxDy, live);
            writeRoadbed(rotating, chunk, cursor, x, minZ, maxZ, live);
        }
        for (int x = minX; x <= maxX; x++) {
            if (!carved[x - minX]) {
                continue;
            }
            sealVaultShell(chunk, cursor, x, minX, maxX, minZ, maxZ, vaultMinZ, vaultMaxZ, trackBlock, carved, vaultMaxDy(rotating, x), live);
        }
    }

    private static void writeRoadbed(
            RotatingChunkGenerator rotating,
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int minZ,
            int maxZ,
            @javax.annotation.Nullable ServerLevel live
    ) {
        ChunkGenerator delegate = rotating.delegates().get(rotating.layout().delegateIndex(
                BandLayout.regionOfBlockX(x, rotating.bandSize())
        ));
        if (!BandLayout.isSurfaceOverworld(delegate) && !BandLayout.isTwilight(delegate)) {
            return;
        }
        int zMin = Math.max(minZ, CORRIDOR_Z - ROADBED_HALF_WIDTH);
        int zMax = Math.min(maxZ, CORRIDOR_Z + ROADBED_HALF_WIDTH);
        for (int z = zMin; z <= zMax; z++) {
            cursor.set(x, TRACK_Y - 1, z);
            BlockState current = chunk.getBlockState(cursor);
            if (current.is(Blocks.BEDROCK)) {
                continue;
            }
            setCell(chunk, live, cursor, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    public static boolean touchesVault(int chunkZ) {
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;
        return maxZ >= CORRIDOR_Z - VAULT_RADIUS && minZ <= CORRIDOR_Z + VAULT_RADIUS;
    }
    /**
     * True when every block column in this chunk lies inside the no-structure zone.
     * Those origin chunks skip {@code createStructures}. Chunks that only overlap
     * the zone still generate; {@link #dropBlockedStarts} filters by AABB.
     */
    public static boolean originFullyInsideNoStructureZone(int chunkZ) {
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;
        int blockRange = NO_STRUCTURE_CHUNK_RANGE * 16 + 15;
        return minZ >= CORRIDOR_Z - blockRange && maxZ <= CORRIDOR_Z + blockRange;
    }

    public static BoundingBox vaultAabb() {
        return new BoundingBox(
                Integer.MIN_VALUE,
                TRACK_Y + VAULT_FLOOR_DY - 1,
                CORRIDOR_Z - VAULT_RADIUS - 1,
                Integer.MAX_VALUE,
                TRACK_Y + VAULT_APEX_DY,
                CORRIDOR_Z + VAULT_RADIUS + 1
        );
    }

    public static boolean intersectsVault(StructureStart start) {
        return start != null && start.isValid() && start.getBoundingBox().intersects(vaultAabb());
    }
    public static boolean intersectsNoStructureZone(StructureStart start) {
        return start != null && start.isValid()
                && start.getBoundingBox().minZ() <= CORRIDOR_Z + NO_STRUCTURE_CHUNK_RANGE * 16 + 15
                && start.getBoundingBox().maxZ() >= CORRIDOR_Z - (NO_STRUCTURE_CHUNK_RANGE * 16 + 15);
    }

    /**
     * Drops starts whose AABB intersects the vault or no-structure zone.
     * Safety net for origins that still generate: overlap-only chunks and
     * out-of-zone origins whose AABB crosses the corridor.
     */
    public static void dropBlockedStarts(ChunkAccess chunk) {
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) {
            return;
        }
        Map<Structure, StructureStart> kept = new HashMap<>();
        boolean dropped = false;
        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (intersectsVault(start) || intersectsNoStructureZone(start)) {
                dropped = true;
                continue;
            }
            kept.put(entry.getKey(), start);
        }
        if (dropped) {
            chunk.setAllStarts(kept);
        }
    }

    /**
     * Octagonal tunnel from the user's 2-1-1-1-2-7 corner staircase, mirrored top to bottom.
     * Flat floor and ceiling are 7 wide (|dz| <= 3); side walls are 7 tall; interior 19 x 19.
     * Interior spans dy = -1..17 (Y=63..81); solid floor Y=62 and ceiling Y=82.
     * Track stays at dy = 0 / Y=64; roadbed stays at Y=63 (the 7-wide floor).
     * Widest dy = 5..11 (|dz| <= 9). Exterior 21 x 21.
     * Per-row half width from the flat: 3, 5, 6, 7, 8, 8, then 9 for the 7 wall rows.
     */
    static boolean inVault(int dz, int dy) {
        if (dy < VAULT_FLOOR_DY || dy > VAULT_APEX_DY) {
            return false;
        }
        int shapeDy = dy - VAULT_FLOOR_DY;
        int apexFromFloor = VAULT_APEX_DY - VAULT_FLOOR_DY;
        int tier = Math.min(shapeDy, apexFromFloor - shapeDy);
        int halfWidth = switch (tier) {
            case 0 -> 3;
            case 1 -> 5;
            case 2 -> 6;
            case 3 -> 7;
            case 4, 5 -> 8;
            default -> VAULT_RADIUS;
        };
        return Math.abs(dz) <= halfWidth;
    }

    /**
     * Blocks tree/fungus features whose origin column crosses the carve band: roots inside the
     * vault z-span (|dz| <= VAULT_RADIUS) and origin anywhere the carving can undermine (below
     * the vault) or decapitate (at or above the ceiling). Trees rooted outside the z-span merely
     * lose canopy edges to the carve — they keep terrain support and are left alone.
     */
    public static boolean blocksTreeLikeOrigin(WorldGenLevel level, BlockPos origin) {
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        int dz = origin.getZ() - CORRIDOR_Z;
        if (Math.abs(dz) > VAULT_RADIUS) {
            return false;
        }
        int maxDy = vaultMaxDy(rotating, origin.getX());
        if (maxDy < 0) {
            return false;
        }
        int dy = origin.getY() - TRACK_Y;
        return dy >= -TREE_UNDERMINED_DEPTH && dy <= maxDy + 1;
    }




    private static void clearVaultColumn(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            Block trackBlock,
            int maxDy,
            boolean carveBedrock,
            @javax.annotation.Nullable ServerLevel live
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = VAULT_FLOOR_DY; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                int y = TRACK_Y + dy;
                if (z == CORRIDOR_Z && y == TRACK_Y) {
                    continue;
                }
                clearTunnelCell(chunk, cursor, x, y, z, trackBlock, carveBedrock, live);
            }
        }
    }

    private static void sealVaultShell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int vaultMinZ,
            int vaultMaxZ,
            Block trackBlock,
            boolean[] carved,
            int maxDy,
            @javax.annotation.Nullable ServerLevel live
    ) {
        if (maxDy < 0) {
            return;
        }
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = VAULT_FLOOR_DY; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                int y = TRACK_Y + dy;
                boolean trackCenter = z == CORRIDOR_Z && y == TRACK_Y;
                if (!trackCenter && !chunk.getBlockState(cursor.set(x, y, z)).isAir()) {
                    continue;
                }
                for (Direction dir : DIRECTIONS) {
                    if (dir.getAxis() == Direction.Axis.X) {
                        int nx = x + dir.getStepX();
                        if (nx < minX || nx > maxX || carved[nx - minX]) {
                            continue;
                        }
                        sealFluidShellCell(chunk, cursor, nx, y, z, minX, maxX, minZ, maxZ, trackBlock, true, maxDy, live);
                        continue;
                    }
                    if (trackCenter && dir != Direction.DOWN) {
                        continue;
                    }
                    sealFluidShellCell(
                            chunk,
                            cursor,
                            x,
                            y + dir.getStepY(),
                            z + dir.getStepZ(),
                            minX,
                            maxX,
                            minZ,
                            maxZ,
                            trackBlock,
                            false,
                            maxDy,
                            live
                    );
                }
            }
        }
    }

    private static void sealFluidShellCell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            Block trackBlock,
            boolean vaultFace,
            int maxDy,
            @javax.annotation.Nullable ServerLevel live
    ) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return;
        }
        if (vaultFace) {
            if (y < TRACK_Y + VAULT_FLOOR_DY || y > TRACK_Y + maxDy || !inVault(z - CORRIDOR_Z, y - TRACK_Y)) {
                return;
            }
        } else if (!isVaultShellCell(y, z, maxDy)) {
            return;
        }
        cursor.set(x, y, z);
        BlockState current = chunk.getBlockState(cursor);
        if (current.is(Blocks.BEDROCK) || current.getBlock() == trackBlock) {
            return;
        }
        BlockState glass = glassForFluid(current);
        if (glass == null || current.is(glass.getBlock())) {
            return;
        }
        setCell(chunk, live, cursor, glass);
    }

    private static boolean isVaultShellCell(int y, int z, int maxDy) {
        int dy = y - TRACK_Y;
        if (dy < VAULT_FLOOR_DY - 1 || dy > maxDy + 1) {
            return false;
        }
        if (dy == VAULT_FLOOR_DY - 1) {
            return true;
        }
        return !inVault(z - CORRIDOR_Z, dy);
    }

    private static void stabilizeVaultCeiling(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            int maxDy,
            @javax.annotation.Nullable ServerLevel live
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = VAULT_FLOOR_DY; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                if (dy < maxDy && inVault(dz, dy + 1)) {
                    continue;
                }
                cursor.set(x, TRACK_Y + dy + 1, z);
                BlockState current = chunk.getBlockState(cursor);
                if (isBamboo(current)) {
                    clearBambooAbove(chunk, live, cursor, x, TRACK_Y + dy + 1, z);
                    continue;
                }
                BlockState stable = stableCeiling(current);
                if (stable != null) {
                    setCell(chunk, live, cursor, stable);
                }
            }
        }
    }

    private static BlockState stableCeiling(BlockState current) {
        if (current.is(Blocks.GRAVEL) || current.is(Blocks.SUSPICIOUS_GRAVEL)) {
            return Blocks.STONE.defaultBlockState();
        }
        if (current.is(Blocks.SAND) || current.is(Blocks.SUSPICIOUS_SAND)) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (current.is(Blocks.RED_SAND)) {
            return Blocks.RED_SANDSTONE.defaultBlockState();
        }
        return null;
    }

    private static boolean isBamboo(BlockState current) {
        return current.is(Blocks.BAMBOO) || current.is(Blocks.BAMBOO_SAPLING);
    }

    private static void clearBambooAbove(
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live,
            BlockPos.MutableBlockPos cursor,
            int x,
            int startY,
            int z
    ) {
        int maxY = chunk.getMaxBuildHeight() - 1;
        for (int y = startY; y <= maxY; y++) {
            cursor.set(x, y, z);
            BlockState current = chunk.getBlockState(cursor);
            if (!isBamboo(current)) {
                return;
            }
            setCell(chunk, live, cursor, Blocks.AIR.defaultBlockState());
        }
    }

    private static BlockState glassForFluid(BlockState current) {
        if (current.getFluidState().is(FluidTags.WATER)) {
            return Blocks.BLUE_STAINED_GLASS.defaultBlockState();
        }
        if (current.getFluidState().is(FluidTags.LAVA)) {
            return Blocks.RED_STAINED_GLASS.defaultBlockState();
        }
        return null;
    }

    private static int vaultMaxDy(RotatingChunkGenerator rotating, int x) {
        return vaultMaxDy(corridorDelegate(rotating, x));
    }

    private static ChunkGenerator corridorDelegate(RotatingChunkGenerator rotating, int x) {
        return rotating.delegates().get(rotating.layout().delegateIndex(
                BandLayout.regionOfBlockX(x, rotating.bandSize())
        ));
    }

    private static int vaultMaxDy(ChunkGenerator delegate) {
        return VAULT_APEX_DY;
    }


    private static void writeTrack(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            Block trackBlock,
            boolean carveBedrock,
            @javax.annotation.Nullable ServerLevel live
    ) {
        cursor.set(x, TRACK_Y, CORRIDOR_Z);
        BlockState current = chunk.getBlockState(cursor);
        if (current.is(Blocks.BEDROCK) && !carveBedrock) {
            return;
        }
        if (current.getBlock() == trackBlock) {
            dryTrack(chunk, live, cursor, current);
            return;
        }
        BlockState track = trackBlock.defaultBlockState()
                .setValue(TrackBlock.SHAPE, TrackShape.XO)
                .setValue(TrackBlock.HAS_BE, false);
        if (track.hasProperty(TrackBlock.WATERLOGGED)) {
            track = track.setValue(TrackBlock.WATERLOGGED, false);
        }
        setCell(chunk, live, cursor, track);
    }

    private static void dryTrack(
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live,
            BlockPos.MutableBlockPos cursor,
            BlockState current
    ) {
        if (!current.hasProperty(TrackBlock.WATERLOGGED) || !current.getValue(TrackBlock.WATERLOGGED)) {
            return;
        }
        setCell(chunk, live, cursor, current.setValue(TrackBlock.WATERLOGGED, false));
    }

    private static void clearTunnelCell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            Block trackBlock,
            boolean carveBedrock,
            @javax.annotation.Nullable ServerLevel live
    ) {
        cursor.set(x, y, z);
        BlockState current = chunk.getBlockState(cursor);
        if ((current.is(Blocks.BEDROCK) && !carveBedrock) || current.isAir()) {
            return;
        }
        if (current.getBlock() == trackBlock) {
            dryTrack(chunk, live, cursor, current);
            return;
        }
        setCell(chunk, live, cursor, Blocks.AIR.defaultBlockState());
    }

    private static void setCell(
            ChunkAccess chunk,
            @javax.annotation.Nullable ServerLevel live,
            BlockPos.MutableBlockPos cursor,
            BlockState state
    ) {
        if (live != null) {
            live.setBlock(cursor.immutable(), state, Block.UPDATE_CLIENTS);
            return;
        }
        chunk.setBlockState(cursor, state, false);
    }

}
