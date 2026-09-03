package com.dimblend.worldgen;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
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
    /** Inclusive |dz| of the vault equator. Diameter 15 = Z[-7, +7]. */
    public static final int VAULT_RADIUS = 7;
    public static final int VAULT_APEX_DY = 14;
    public static final ResourceLocation TRACK_ID = ResourceLocation.fromNamespaceAndPath("railways", "track_create_andesite_wide");

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
        for (int x = minX; x <= maxX; x++) {
            if (!allowsTunnel(rotating, x) || !isBuried(chunk, cursor, x, vaultMinZ, vaultMaxZ)) {
                continue;
            }
            clearVaultColumn(chunk, cursor, x, vaultMinZ, vaultMaxZ, trackBlock, false);
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
            if (!allowsTunnel(rotating, x)) {
                continue;
            }
            clearVaultColumn(chunk, cursor, x, vaultMinZ, vaultMaxZ, trackBlock, true);
        }
    }

    public static boolean touchesVault(int chunkZ) {
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;
        return maxZ >= CORRIDOR_Z - VAULT_RADIUS && minZ <= CORRIDOR_Z + VAULT_RADIUS;
    }

    /**
     * Raised circular horseshoe: radius 8, center 6.5 blocks above the track.
     * Integer form (2*dy - 13)^2 + 4*dz^2 <= 256, dy >= 0.
     * Floor |dz| <= 4 (9 wide); equator |dz| <= 7 (diameter 15); apex dy = 14.
     * z = +/-5..7 starts above the track, never from dy = 0.
     */
    static boolean inVault(int dz, int dy) {
        if (dy < 0) {
            return false;
        }
        int twoDyMinus13 = dy + dy - 13;
        return twoDyMinus13 * twoDyMinus13 + 4 * dz * dz <= 256;
    }


    private static void clearVaultColumn(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ,
            Block trackBlock,
            boolean treesOnly
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = 0; dy <= VAULT_APEX_DY; dy++) {
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

    private static boolean allowsTunnel(RotatingChunkGenerator rotating, int x) {
        ChunkGenerator delegate = rotating.delegates().get(
                BandIndex.ofBlockX(x, rotating.bandSize(), rotating.delegates().size())
        );
        if (!(delegate instanceof SlicedOverworldChunkGenerator sliced)) {
            return true;
        }
        OverworldSlice slice = sliced.slice();
        return TRACK_Y >= slice.targetMinY() && TRACK_Y + VAULT_APEX_DY < slice.targetMaxExclusiveY();
    }

    private static boolean isBuried(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos cursor,
            int x,
            int vaultMinZ,
            int vaultMaxZ
    ) {
        for (int z = vaultMinZ; z <= vaultMaxZ; z++) {
            int dz = z - CORRIDOR_Z;
            for (int dy = 0; dy <= VAULT_APEX_DY; dy++) {
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
