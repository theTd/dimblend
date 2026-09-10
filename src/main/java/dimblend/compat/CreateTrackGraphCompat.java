package dimblend.compat;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.OakTrackCorridor;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class CreateTrackGraphCompat {
    private static final int STITCH_PLAYER_CHUNK_RANGE = 8;
    private static final int PENDING_STITCH_CAP = 4096;
    private static final LinkedHashSet<ChunkPos> pendingStitch = new LinkedHashSet<>();

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
        if (pos.z != OakTrackCorridor.CORRIDOR_Z) {
            return;
        }
        MinecraftServer server = level.getServer();
        server.tell(new TickTask(server.getTickCount() + 1, () -> tryStitchOrDefer(level, pos)));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            return;
        }
        if (pendingStitch.isEmpty()) {
            return;
        }
        List<ChunkPos> snapshot = new ArrayList<>(pendingStitch);
        for (ChunkPos pos : snapshot) {
            if (!playerNearTrackChunk(level, pos)) {
                continue;
            }
            pendingStitch.remove(pos);
            stitchChunkEnds(level, pos);
            break;
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        pendingStitch.clear();
    }

    private static void tryStitchOrDefer(ServerLevel level, ChunkPos pos) {
        if (!playerNearTrackChunk(level, pos)) {
            pendingStitch.add(pos);
            while (pendingStitch.size() > PENDING_STITCH_CAP) {
                pendingStitch.removeFirst();
            }
            return;
        }
        pendingStitch.remove(pos);
        stitchChunkEnds(level, pos);
    }

    private static boolean playerNearTrackChunk(ServerLevel level, ChunkPos pos) {
        for (ServerPlayer player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            if (Math.max(Math.abs(playerChunk.x - pos.x), Math.abs(playerChunk.z - pos.z))
                    <= STITCH_PLAYER_CHUNK_RANGE) {
                return true;
            }
        }
        return false;
    }

    private static void stitchChunkEnds(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        int minX = pos.getMinBlockX();
        int maxX = pos.getMaxBlockX();
        int y = OakTrackCorridor.TRACK_Y;
        int z = OakTrackCorridor.CORRIDOR_Z;
        BlockPos west = null;
        BlockPos east = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            cursor.set(x, y, z);
            if (!(chunk.getBlockState(cursor).getBlock() instanceof TrackBlock)) {
                continue;
            }
            BlockPos found = cursor.immutable();
            if (west == null) {
                west = found;
            }
            east = found;
        }
        if (west == null) {
            return;
        }
        LevelAccessor loadedOnly = loadedChunksOnly(level);
        stitchEndIfMissing(level, loadedOnly, chunk, west);
        if (!west.equals(east)) {
            stitchEndIfMissing(level, loadedOnly, chunk, east);
        }
    }

    private static void stitchEndIfMissing(
            ServerLevel level,
            LevelAccessor loadedOnly,
            LevelChunk chunk,
            BlockPos end
    ) {
        if (alreadyInGraph(level, end)) {
            return;
        }
        BlockState state = chunk.getBlockState(end);
        TrackPropagator.onRailAdded(loadedOnly, end.immutable(), state);
    }

    private static boolean alreadyInGraph(ServerLevel level, BlockPos pos) {
        TrackNodeLocation loc = new TrackNodeLocation(Vec3.atLowerCornerOf(pos)).in(level);
        return !Create.RAILWAYS.sided(level).getGraphs(level, loc).isEmpty();
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
