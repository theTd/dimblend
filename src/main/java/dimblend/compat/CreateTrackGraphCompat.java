package dimblend.compat;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.OakTrackCorridor;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import java.lang.reflect.Proxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;

public final class CreateTrackGraphCompat {
    private CreateTrackGraphCompat() {
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        if (!event.isNewChunk()) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        if (!OakTrackCorridor.touchesVault(pos.z)) {
            return;
        }
        MinecraftServer server = level.getServer();
        server.tell(new TickTask(server.getTickCount() + 1, () -> recarveLoadedVaultChunk(level, pos)));
    }

    private static void recarveLoadedVaultChunk(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        OakTrackCorridor.reclearLoadedChunk(level, chunk);
        if (pos.z != 0) {
            return;
        }
        LevelAccessor loadedOnly = loadedChunksOnly(level);
        int minX = pos.getMinBlockX();
        int maxX = pos.getMaxBlockX();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            cursor.set(x, OakTrackCorridor.TRACK_Y, OakTrackCorridor.CORRIDOR_Z);
            BlockState state = chunk.getBlockState(cursor);
            if (state.getBlock() instanceof TrackBlock) {
                TrackPropagator.onRailAdded(loadedOnly, cursor.immutable(), state);
            }
        }
    }

    private static LevelAccessor loadedChunksOnly(ServerLevel level) {
        return (LevelAccessor) Proxy.newProxyInstance(
                LevelAccessor.class.getClassLoader(),
                new Class<?>[] {LevelAccessor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getBlockState") && args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                        return blockStateIfLoaded(level, pos);
                    }
                    if (name.equals("getFluidState") && args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                        return blockStateIfLoaded(level, pos).getFluidState();
                    }
                    if (name.equals("getBlockEntity") && args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                        return blockEntityIfLoaded(level, pos);
                    }
                    if (name.equals("isClientSide")) {
                        return false;
                    }
                    return method.invoke(level, args);
                }
        );
    }

    private static BlockState blockStateIfLoaded(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())
        );
        if (chunk == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return chunk.getBlockState(pos);
    }

    private static BlockEntity blockEntityIfLoaded(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())
        );
        if (chunk == null) {
            return null;
        }
        return chunk.getBlockEntity(pos);
    }
}
