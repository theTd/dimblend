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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CreateTrackGraphCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("dimblend/TrackStitch");
    private static final int STITCH_PLAYER_CHUNK_RANGE = 8;
    private static final int PENDING_STITCH_CAP = 4096;
    /** Near-player deferred stitches to drain per server tick. */
    private static final int MAX_STITCH_PER_TICK = 8;
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
        // No isNewChunk gate: worldgen tracks carry HAS_BE=false and no BlockEntity, so
        // TrackBlock.tick's TrackPropagator.onRailAdded never fires for them. The stitch is
        // the only graph-registration path, and rotating graphs are not persisted by Create,
        // so disk-loaded chunks must re-stitch on every load. alreadyInGraph + onRailAdded's
        // merge-into-existing-graph behavior keep repeats cheap.
        ChunkPos pos = event.getChunk().getPos();
        if (pos.z != OakTrackCorridor.CORRIDOR_Z) {
            return;
        }
        MinecraftServer server = level.getServer();
        // Enqueue only — never stitch inline. TickTasks run between ticks (runAllTasks,
        // outside ServerTickEvent): an inline stitch there once blocked the server thread
        // for 15-35s per corridor chunk (invisible to spark's tick stats, logged by
        // vanilla as "Can't keep up! Running Xms behind"). The actual stitch work is
        // drained inside onServerTick under a per-tick budget.
        server.tell(new TickTask(server.getTickCount() + 1, () -> enqueueStitch(pos)));
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
        int processed = 0;
        for (ChunkPos pos : snapshot) {
            if (processed >= MAX_STITCH_PER_TICK) {
                break;
            }
            if (!playerNearTrackChunk(level, pos)) {
                continue;
            }
            if (!neighborsLoaded(level, pos)) {
                // Walk/getConnected reads reach +/-1 block past the chunk borders; wait
                // until the 4-neighborhood is loaded so TrackPropagator.onRailAdded (and
                // its TrackPropagatorMixin-bounded walk) can never force a sync chunk
                // load. Stay queued; the neighbor's ChunkEvent.Load re-enqueues work and
                // this entry is retried on a later tick.
                continue;
            }
            pendingStitch.remove(pos);
            stitchChunkEnds(level, pos);
            processed++;
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        pendingStitch.clear();
    }

    private static void enqueueStitch(ChunkPos pos) {
        pendingStitch.add(pos);
        while (pendingStitch.size() > PENDING_STITCH_CAP) {
            pendingStitch.removeFirst();
        }
        LOGGER.debug("queue {} pending={}", pos, pendingStitch.size());
    }

    /**
     * True when the 4-neighborhood chunks of pos are all loaded (non-blocking).
     * The corridor track sits at z=0, i.e. on the border between z-chunks -1 and 0,
     * and onRailAdded's blockstate reads reach +/-1 block, hence the z-neighbors.
     */
    public static boolean neighborsLoaded(ServerLevel level, ChunkPos pos) {
        return chunkNow(level, pos.x - 1, pos.z) != null
                && chunkNow(level, pos.x + 1, pos.z) != null
                && chunkNow(level, pos.x, pos.z - 1) != null
                && chunkNow(level, pos.x, pos.z + 1) != null;
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
        // getChunkNow, not getChunk: a deferred entry may outlive the chunk's stay in the
        // loaded map, and ServerLevel.getChunk would sync-load it on the main thread here.
        // The next ChunkEvent.Load re-enqueues it.
        LevelChunk chunk = chunkNow(level, pos.x, pos.z);
        if (chunk == null) {
            return;
        }
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
            LOGGER.debug("stitch {} no track row found", pos);
            return;
        }
        // The real ServerLevel, not a LevelAccessor proxy: Create resolves node dimensions via
        // `world instanceof Level` (ITrackBlock.lambda$getConnected$1) and silently tags every
        // node as minecraft:overworld otherwise, making the whole registration inert.
        // Sync chunk loads inside onRailAdded's walk are prevented upstream: the drain only
        // runs when the 4-neighborhood is loaded, and TrackPropagatorMixin bounds the walk
        // to loaded chunks inside the rotating dimension.
        stitchEndIfMissing(level, chunk, west);
        if (!west.equals(east)) {
            stitchEndIfMissing(level, chunk, east);
        }
    }

    private static void stitchEndIfMissing(
            ServerLevel level,
            LevelChunk chunk,
            BlockPos end
    ) {
        if (alreadyInGraph(level, end)) {
            return;
        }
        BlockState state = chunk.getBlockState(end);
        LOGGER.debug("stitch onRailAdded at {} state={}", end, state);
        TrackPropagator.onRailAdded(level, end.immutable(), state);
        LOGGER.debug("stitch onRailAdded done at {} graphsAtLoc={}", end, alreadyInGraph(level, end));
    }

    private static boolean alreadyInGraph(ServerLevel level, BlockPos pos) {
        // Create places graph nodes at the block's bottom center (TrackBlock.getConnected
        // uses Vec3.atBottomCenterOf); a corner lookup never matches and would rerun the
        // full onRailAdded walk on every load.
        TrackNodeLocation loc = new TrackNodeLocation(Vec3.atBottomCenterOf(pos)).in(level);
        return !Create.RAILWAYS.sided(level).getGraphs(level, loc).isEmpty();
    }

    private static LevelChunk chunkNow(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x, z);
    }

    /**
     * Read-only view that reports AIR for chunks not in the loaded map. NOTE: this is a
     * LevelAccessor proxy, not a Level — Create resolves node dimensions via
     * {@code world instanceof Level} and would tag every discovered node as overworld,
     * making the whole registration inert. Only suitable for pure blockstate reads
     * that never feed TrackNodeLocation creation.
     */
    public static LevelAccessor loadedChunksOnly(ServerLevel level) {
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
        LevelChunk chunk = chunkNow(
                level,
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())
        );
        if (chunk == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return chunk.getBlockState(pos);
    }

    private static BlockEntity blockEntityIfLoaded(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = chunkNow(
                level,
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())
        );
        if (chunk == null) {
            return null;
        }
        return chunk.getBlockEntity(pos);
    }
}
