package com.dimblend.worldgen;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

public final class OakTrackCorridor {
    public static final int CORRIDOR_Z = 0;
    public static final int TRACK_Y = 64;
    public static final int TUNNEL_HALF_WIDTH = 2;
    public static final int TUNNEL_HEIGHT = 5;
    public static final ResourceLocation TRACK_ID = ResourceLocation.fromNamespaceAndPath("railways", "track_oak");

    private OakTrackCorridor() {
    }

    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        int chunkZ = chunk.getPos().z;
        if (chunkZ != 0 && chunkZ != -1) {
            return;
        }
        ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        Block trackBlock = level.registryAccess().registryOrThrow(Registries.BLOCK).get(TRACK_ID);
        if (trackBlock == null) {
            throw new IllegalStateException("missing required block railways:track_oak");
        }

        int minX = chunk.getPos().getMinBlockX();
        int maxX = chunk.getPos().getMaxBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = chunk.getPos().getMaxBlockZ();
        int tunnelMinZ = Math.max(minZ, CORRIDOR_Z - TUNNEL_HALF_WIDTH);
        int tunnelMaxZ = Math.min(maxZ, CORRIDOR_Z + TUNNEL_HALF_WIDTH);
        if (tunnelMinZ > tunnelMaxZ) {
            return;
        }

        RandomState randomState = level.getLevel().getChunkSource().randomState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int tunnelMinY = TRACK_Y;
        int tunnelMaxY = TRACK_Y + TUNNEL_HEIGHT - 1;
        if (chunkZ == 0) {
            for (int x = minX; x <= maxX; x++) {
                writeTrack(level, chunk, cursor, x, trackBlock);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            if (!allowsTunnel(rotating, x) || !isBuried(rotating, x, level, randomState, tunnelMinY, tunnelMaxY)) {
                continue;
            }
            for (int z = tunnelMinZ; z <= tunnelMaxZ; z++) {
                for (int y = tunnelMinY; y <= tunnelMaxY; y++) {
                    if (z == CORRIDOR_Z && y == TRACK_Y) {
                        continue;
                    }
                    clearTunnelCell(chunk, cursor, x, y, z, trackBlock, false);
                }
            }
        }
    }

    public static void reclearLoadedChunk(ServerLevel level, ChunkAccess chunk) {
        int chunkZ = chunk.getPos().z;
        if (chunkZ != 0 && chunkZ != -1) {
            return;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        Block trackBlock = level.registryAccess().registryOrThrow(Registries.BLOCK).get(TRACK_ID);
        if (trackBlock == null) {
            throw new IllegalStateException("missing required block railways:track_oak");
        }
        int minX = chunk.getPos().getMinBlockX();
        int maxX = chunk.getPos().getMaxBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = chunk.getPos().getMaxBlockZ();
        int tunnelMinZ = Math.max(minZ, CORRIDOR_Z - TUNNEL_HALF_WIDTH);
        int tunnelMaxZ = Math.min(maxZ, CORRIDOR_Z + TUNNEL_HALF_WIDTH);
        if (tunnelMinZ > tunnelMaxZ) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int tunnelMinY = TRACK_Y;
        int tunnelMaxY = TRACK_Y + TUNNEL_HEIGHT - 1;
        for (int x = minX; x <= maxX; x++) {
            if (!allowsTunnel(rotating, x)) {
                continue;
            }
            for (int z = tunnelMinZ; z <= tunnelMaxZ; z++) {
                for (int y = tunnelMinY; y <= tunnelMaxY; y++) {
                    if (z == CORRIDOR_Z && y == TRACK_Y) {
                        continue;
                    }
                    clearTunnelCell(chunk, cursor, x, y, z, trackBlock, true);
                }
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
        return TRACK_Y >= slice.targetMinY() && TRACK_Y + TUNNEL_HEIGHT - 1 < slice.targetMaxExclusiveY();
    }

    private static boolean isBuried(
            RotatingChunkGenerator rotating,
            int x,
            WorldGenLevel level,
            RandomState randomState,
            int tunnelMinY,
            int tunnelMaxY
    ) {
        for (int z = CORRIDOR_Z - TUNNEL_HALF_WIDTH; z <= CORRIDOR_Z + TUNNEL_HALF_WIDTH; z++) {
            NoiseColumn column = rotating.getBaseColumn(x, z, level, randomState);
            for (int y = tunnelMinY; y <= tunnelMaxY; y++) {
                if (z == CORRIDOR_Z && y == TRACK_Y) {
                    continue;
                }
                if (isSolidMountain(column.getBlock(y))) {
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
