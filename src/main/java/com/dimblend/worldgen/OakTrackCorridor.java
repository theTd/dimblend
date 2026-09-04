package com.dimblend.worldgen;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;

public final class OakTrackCorridor {
    public static final int CORRIDOR_Z = 0;
    public static final int TRACK_Y = 64;
    /** Inclusive |dz| of the vault equator. Diameter 23 = Z[-11, +11]. */
    public static final int VAULT_RADIUS = 11;
    public static final int VAULT_APEX_DY = 18;
    public static final ResourceLocation TRACK_ID = ResourceLocation.fromNamespaceAndPath("railways", "track_create_andesite_wide");
    private static final Direction[] DIRECTIONS = Direction.values();

    private OakTrackCorridor() {
    }

    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        int chunkZ = chunk.getPos().z;
        if (!touchesVault(chunkZ)) {
            return;
        }
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        Block trackBlock = level.registryAccess().registryOrThrow(Registries.BLOCK).get(TRACK_ID);
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
        if (chunkZ == 0) {
            for (int x = minX; x <= maxX; x++) {
                writeTrack(level, chunk, cursor, x, trackBlock);
            }
        }
        boolean[] carved = new boolean[maxX - minX + 1];
        for (int x = minX; x <= maxX; x++) {
            int maxDy = vaultMaxDy(rotating, x);
            if (maxDy < 0 || !isBuried(chunk, cursor, x, vaultMinZ, vaultMaxZ, maxDy)) {
                continue;
            }
            carved[x - minX] = true;
            clearVaultColumn(chunk, cursor, x, vaultMinZ, vaultMaxZ, trackBlock, false, maxDy);
            stabilizeVaultCeiling(chunk, cursor, x, vaultMinZ, vaultMaxZ, maxDy);
        }
        for (int x = minX; x <= maxX; x++) {
            if (!carved[x - minX]) {
                continue;
            }
            sealVaultShell(chunk, cursor, x, minX, maxX, minZ, maxZ, vaultMinZ, vaultMaxZ, trackBlock, carved, vaultMaxDy(rotating, x));
        }
    }

    public static void reclearLoadedChunk(ServerLevel level, ChunkAccess chunk) {
        int chunkZ = chunk.getPos().z;
        if (!touchesVault(chunkZ)) {
            return;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        Block trackBlock = level.registryAccess().registryOrThrow(Registries.BLOCK).get(TRACK_ID);
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
        for (int x = minX; x <= maxX; x++) {
            int maxDy = vaultMaxDy(rotating, x);
            if (maxDy < 0) {
                continue;
            }
            clearVaultColumn(chunk, cursor, x, vaultMinZ, vaultMaxZ, trackBlock, true, maxDy);
        }
    }

    public static boolean touchesVault(int chunkZ) {
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;
        return maxZ >= CORRIDOR_Z - VAULT_RADIUS && minZ <= CORRIDOR_Z + VAULT_RADIUS;
    }

    /**
     * Raised circular horseshoe: radius 12, center 6.5 blocks above the track.
     * Integer form (2*dy - 13)^2 + 4*dz^2 <= 576, dy >= 0.
     * Floor |dz| <= 10 (21 wide); equator |dz| <= 11 (diameter 23); apex dy = 18.
     * z = +/-11 starts at dy = 2, never from dy = 0.
     */
    static boolean inVault(int dz, int dy) {
        if (dy < 0) {
            return false;
        }
        int twoDyMinus13 = dy + dy - 13;
        return twoDyMinus13 * twoDyMinus13 + 4 * dz * dz <= 576;
    }


    private static void clearVaultColumn(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            Block trackBlock,
            boolean treesOnly,
            int maxDy
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = 0; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                int y = TRACK_Y + dy;
                if (z == CORRIDOR_Z && y == TRACK_Y) {
                    continue;
                }
                clearTunnelCell(chunk, cursor, x, y, z, trackBlock, treesOnly);
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
            int maxDy
    ) {
        if (maxDy < 0) {
            return;
        }
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = 0; dy <= maxDy; dy++) {
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
                        sealFluidShellCell(chunk, cursor, nx, y, z, minX, maxX, minZ, maxZ, trackBlock, true, maxDy);
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
                            maxDy
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
            int maxDy
    ) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return;
        }
        if (y > TRACK_Y + maxDy) {
            return;
        }
        if (vaultFace) {
            if (y < TRACK_Y || !inVault(z - CORRIDOR_Z, y - TRACK_Y)) {
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
        chunk.setBlockState(cursor, glass, false);
    }

    private static boolean isVaultShellCell(int y, int z, int maxDy) {
        if (y == TRACK_Y - 1) {
            return true;
        }
        if (y > TRACK_Y + maxDy) {
            return false;
        }
        return y >= TRACK_Y && !inVault(z - CORRIDOR_Z, y - TRACK_Y);
    }

    private static void stabilizeVaultCeiling(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            int maxDy
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = 0; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                if (dy < maxDy && inVault(dz, dy + 1)) {
                    continue;
                }
                cursor.set(x, TRACK_Y + dy + 1, z);
                BlockState current = chunk.getBlockState(cursor);
                BlockState stable = stableCeiling(current);
                if (stable != null) {
                    chunk.setBlockState(cursor, stable, false);
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
        ChunkGenerator delegate = rotating.delegates().get(rotating.layout().delegateIndex(
                BandLayout.regionOfBlockX(x, rotating.bandSize())
        ));
        if (!(delegate instanceof SlicedOverworldChunkGenerator sliced)) {
            return VAULT_APEX_DY;
        }
        OverworldSlice slice = sliced.slice();
        if (TRACK_Y < slice.targetMinY() || TRACK_Y >= slice.targetMaxExclusiveY()) {
            return -1;
        }
        return Math.min(VAULT_APEX_DY, slice.targetMaxExclusiveY() - 1 - TRACK_Y);
    }

    private static boolean isBuried(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            int maxDy
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = 0; dy <= maxDy; dy++) {
                if (!inVault(dz, dy)) {
                    continue;
                }
                int y = TRACK_Y + dy;
                if (z == CORRIDOR_Z && y == TRACK_Y) {
                    continue;
                }
                if (isSolidMountain(chunk.getBlockState(cursor.set(x, y, z)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void writeTrack(
            WorldGenLevel level,
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            Block trackBlock
    ) {
        cursor.set(x, TRACK_Y, CORRIDOR_Z);
        BlockState current = chunk.getBlockState(cursor);
        if (!canReplace(current, trackBlock)) {
            return;
        }
        BlockState track = trackBlock.defaultBlockState()
                .setValue(TrackBlock.SHAPE, TrackShape.XO)
                .setValue(TrackBlock.HAS_BE, false);
        if (!current.getFluidState().isEmpty() && track.hasProperty(TrackBlock.WATERLOGGED)) {
            track = track.setValue(TrackBlock.WATERLOGGED, true);
        }
        chunk.setBlockState(cursor, track, false);
    }

    private static void clearTunnelCell(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            Block trackBlock,
            boolean treesOnly
    ) {
        cursor.set(x, y, z);
        BlockState current = chunk.getBlockState(cursor);
        if (current.is(Blocks.BEDROCK) || current.getBlock() == trackBlock || current.isAir()) {
            return;
        }
        if (treesOnly && !isTree(current)) {
            return;
        }
        chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
    }

    private static boolean canReplace(BlockState current, Block trackBlock) {
        if (current.is(Blocks.BEDROCK)) {
            return false;
        }
        return current.getBlock() != trackBlock;
    }

    private static boolean isSolidMountain(BlockState current) {
        return current.blocksMotion() && !current.is(Blocks.BEDROCK);
    }

    private static boolean isTree(BlockState current) {
        return current.is(BlockTags.LOGS) || current.is(BlockTags.LEAVES);
    }
}
