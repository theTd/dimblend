package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class PregenController {
    private static final int RESCAN_INTERVAL_TICKS = 20;
    private static final int WATCHDOG_TICKS = 3600;
    private static final int POOL_BACKLOG_PER_WORKER = 1;
    private static final int TICKET_TIMEOUT_TICKS = 1200;
    private static final int CONGEST_TRIGGER_TICKS = 10;
    private static final TicketType<ChunkPos> PREGEN_TICKET =
            TicketType.create("dimblend:pregen", Comparator.comparingLong(ChunkPos::toLong), TICKET_TIMEOUT_TICKS);

    public record Snapshot(
            boolean enabled,
            String overrideMarker,
            int window,
            int done,
            int inFlight,
            int cancelling,
            int pending,
            int behind,
            int behindDone,
            int ahead,
            int aheadDone,
            int cap,
            int maxInFlight,
            int anchors,
            int online,
            String mesh,
            int cancelledThisCycle,
            double avgTickMs,
            int xBehind,
            int xAhead,
            int zMin,
            int zMax
    ) {
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("pregen " + (this.enabled ? "on" : "off")
                    + "  cap " + this.cap + "/" + this.maxInFlight
                    + "  fly " + this.inFlight
                    + "  canc " + this.cancelling
                    + "  wait " + this.pending
                    + "  " + String.format("%.0f", this.avgTickMs) + "ms"
                    + "  online " + this.online
                    + "  mesh " + this.mesh
                    + "  cancel " + this.cancelledThisCycle
                    + "  logout " + this.anchors
                    + this.overrideMarker);
            lines.add("all    " + bar(this.done, this.window) + "  " + this.done + "/" + this.window);
            lines.add("behind " + bar(this.behindDone, this.behind) + "  " + this.behindDone + "/" + this.behind
                    + "  x-" + this.xBehind);
            lines.add("ahead  " + bar(this.aheadDone, this.ahead) + "  " + this.aheadDone + "/" + this.ahead
                    + "  x+" + this.xAhead);
            lines.add("strip  z=[" + this.zMin + "," + this.zMax + "]");
            return lines;
        }

        private static String bar(int filled, int total) {
            int width = 24;
            if (total <= 0) {
                return "[" + "-".repeat(width) + "]   --%";
            }
            int cells = Math.min(width, (int) Math.round(width * (double) filled / total));
            int pct = (int) Math.round(100.0 * filled / total);
            return "[" + "#".repeat(cells) + ".".repeat(width - cells) + "] " + String.format("%3d", pct) + "%";
        }
    }

    private record WindowStats(
            int window,
            int done,
            int behind,
            int behindDone,
            int ahead,
            int aheadDone,
            int online
    ) {
    }

    /** Chunk keys are packed positions of the rotating dimension only; no dimension field needed. */
    private final Deque<Long> queue = new ArrayDeque<>();
    private final LongOpenHashSet done = new LongOpenHashSet();
    private final Long2IntOpenHashMap inFlight = new Long2IntOpenHashMap();
    private final LongOpenHashSet cancelling = new LongOpenHashSet();
    private final Map<UUID, Integer> logoutAnchors = new ConcurrentHashMap<>();
    private volatile int cap;
    private volatile boolean stopping;
    private volatile Boolean pregenOverride;
    private int tickCounter;
    private int healthyStreak;
    private int windowMissing;
    private int backlogStreak;
    private int cancelledThisCycle;
    private int congestedStreak;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.stopping) {
            return;
        }
        MinecraftServer server = event.getServer();
        this.cancelledThisCycle = 0;
        this.sweepCompleted(server);
        if (!pregenEffective()) {
            return;
        }
        this.cancelInflightDueToBacklog(server);
        this.cancelInflightForMeshCongestion(server);
        this.adjustCap(server);
        this.issueTickets(server);
        if (++this.tickCounter >= RESCAN_INTERVAL_TICKS) {
            this.tickCounter = 0;
            this.rebuildWindows(server);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        this.stopping = true;
        this.pregenOverride = null;
        this.queue.clear();
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        cancelInflightTickets(level);
        this.inFlight.clear();
        this.cancelling.clear();
        this.done.clear();
        this.logoutAnchors.clear();
        this.cap = PregenConfig.MIN_IN_FLIGHT.get();
        this.tickCounter = 0;
        this.healthyStreak = 0;
        this.windowMissing = 0;
        this.backlogStreak = 0;
        this.cancelledThisCycle = 0;
        this.congestedStreak = 0;
        this.stopping = false;
    }

    /** Runtime on/off/auto switch; null means follow the config value. */
    public boolean pregenEffective() {
        Boolean override = this.pregenOverride;
        return override != null ? override : PregenConfig.ENABLED.get();
    }

    /**
     * Sets the runtime override. Turning pregen off revokes all non-cancelling in-flight tickets
     * so generation stops immediately instead of waiting for the 60s ticket timeout.
     */
    public void setPregenOverride(Boolean override) {
        this.pregenOverride = override;
        if (override != null && !override) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
                if (level != null) {
                    cancelInflightTickets(level);
                }
            }
        }
    }

    private String overrideMarker() {
        Boolean override = this.pregenOverride;
        return override == null ? "" : "  ovr:" + (override ? "on" : "off");
    }

    /**
     * Revokes every non-cancelling in-flight ticket and marks those chunks as cancelling so
     * {@link #sweepCompleted(MinecraftServer)} will not remove their tickets a second time
     * once they arrive.
     */
    private void cancelInflightTickets(ServerLevel level) {
        if (level == null) {
            return;
        }
        for (long chunk : this.inFlight.keySet()) {
            if (this.cancelling.contains(chunk)) {
                continue;
            }
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
            this.cancelling.add(chunk);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.serverLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            this.logoutAnchors.remove(serverPlayer.getUUID());
            return;
        }
        this.logoutAnchors.put(serverPlayer.getUUID(), serverPlayer.chunkPosition().x);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.serverLevel().dimension() == DimBlendRegistries.ROTATING_LEVEL) {
            this.logoutAnchors.remove(serverPlayer.getUUID());
        }
    }

    public Snapshot snapshot(MinecraftServer server) {
        int xBehind = PregenConfig.X_BEHIND.get();
        int xAhead = PregenConfig.PREGEN_ONLY_BEHIND.get() ? 0 : PregenConfig.X_AHEAD.get();
        int zMin = PregenConfig.Z_MIN.get();
        int zMax = PregenConfig.Z_MAX.get();
        WindowStats stats = this.windowStats(server, xBehind, xAhead, zMin, zMax);
        return new Snapshot(
                this.pregenEffective(),
                this.overrideMarker(),
                stats.window,
                stats.done,
                this.inFlight.size(),
                this.cancelling.size(),
                Math.max(0, stats.window - stats.done - this.inFlight.size()),
                stats.behind,
                stats.behindDone,
                stats.ahead,
                stats.aheadDone,
                this.cap,
                PregenConfig.MAX_IN_FLIGHT.get(),
                this.logoutAnchors.size(),
                stats.online,
                meshStatus(),
                this.cancelledThisCycle,
                averageTickMs(server),
                xBehind,
                xAhead,
                zMin,
                zMax
        );
    }

    private WindowStats windowStats(MinecraftServer server, int xBehind, int xAhead, int zMin, int zMax) {
        Long2IntOpenHashMap side = new Long2IntOpenHashMap();
        int online = 0;
        HashSet<UUID> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            seen.add(player.getUUID());
            if (player.serverLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) {
                continue;
            }
            online++;
            this.addWindow(side, player.chunkPosition().x, player.chunkPosition().z, xBehind, xAhead, zMin, zMax);
        }
        for (Map.Entry<UUID, Integer> entry : this.logoutAnchors.entrySet()) {
            if (seen.contains(entry.getKey())) {
                continue;
            }
            this.addWindow(side, entry.getValue(), 0, xBehind, xAhead, zMin, zMax);
        }
        int window = side.size();
        int done = 0;
        int behind = 0;
        int behindDone = 0;
        int ahead = 0;
        int aheadDone = 0;
        for (Long2IntMap.Entry entry : side.long2IntEntrySet()) {
            boolean finished = this.done.contains(entry.getLongKey());
            if (finished) {
                done++;
            }
            if (entry.getIntValue() < 0) {
                behind++;
                if (finished) {
                    behindDone++;
                }
            } else {
                ahead++;
                if (finished) {
                    aheadDone++;
                }
            }
        }
        return new WindowStats(window, done, behind, behindDone, ahead, aheadDone, online);
    }

    private void adjustCap(MinecraftServer server) {
        int min = Math.min(PregenConfig.MIN_IN_FLIGHT.get(), PregenConfig.MAX_IN_FLIGHT.get());
        int max = Math.max(min, PregenConfig.MAX_IN_FLIGHT.get());
        if (this.cap == 0) {
            this.cap = min;
        } else if (this.cap > max) {
            this.cap = max;
        }

        boolean backlogged = worldgenPoolBacklogged();
        boolean gateEnabled = PregenConfig.MESH_GATE.get();
        boolean congested = gateEnabled && MeshPressure.current() == MeshPressure.Signal.CONGESTED;
        if (congested) {
            this.congestedStreak++;
        } else {
            this.congestedStreak = 0;
        }
        double avgMs = averageTickMs(server);

        boolean brake = avgMs > PregenConfig.BRAKE_TICK_MS.get() || backlogged
                || (gateEnabled && this.congestedStreak >= CONGEST_TRIGGER_TICKS);
        if (brake) {
            this.cap = Math.max(1, this.cap / 2);
            if (backlogged || congestedStreak >= CONGEST_TRIGGER_TICKS) {
                this.cap = Math.min(this.cap, min);
            }
            this.healthyStreak = 0;
            return;
        }
        if (avgMs >= PregenConfig.OK_TICK_MS.get()) {
            this.healthyStreak = 0;
            return;
        }
        this.healthyStreak++;
        if (this.healthyStreak < PregenConfig.RAISE_STREAK_TICKS.get()) {
            return;
        }
        this.healthyStreak = 0;
        if (this.cap >= max || !this.hasDemand() || backlogged) {
            return;
        }
        this.cap = this.cap < min ? min : this.cap + 1;
    }

    private boolean hasDemand() {
        int active = this.inFlight.size() - this.cancelling.size();
        return this.windowMissing > 0 || active >= this.cap || !this.queue.isEmpty();
    }

    private static double averageTickMs(MinecraftServer server) {
        long sum = 0;
        int n = 0;
        for (long v : server.getTickTimesNanos()) {
            if (v > 0) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0 : sum / 1_000_000.0 / n;
    }

    private static boolean worldgenPoolBacklogged() {
        ExecutorService executor = Util.backgroundExecutor();
        if (!(executor instanceof ForkJoinPool pool)) {
            return false;
        }
        int queued = pool.getQueuedSubmissionCount();
        int limit = Math.max(1, pool.getParallelism() * POOL_BACKLOG_PER_WORKER);
        return queued > limit;
    }

    private void cancelInflightDueToBacklog(MinecraftServer server) {
        if (!PregenConfig.CANCEL_ON_POOL_BACKLOG.get()) {
            this.backlogStreak = 0;
            return;
        }
        if (!worldgenPoolBacklogged()) {
            this.backlogStreak = 0;
            return;
        }
        this.backlogStreak++;
        int streak = PregenConfig.BACKLOG_CANCEL_STREAK.get();
        if (this.backlogStreak < streak || this.inFlight.isEmpty()) {
            return;
        }
        this.backlogStreak = 0;
        int toCancel = Math.max(1, this.inFlight.size() / 2);
        List<Long> victims = new ArrayList<>(this.inFlight.keySet());
        victims.removeIf(this.cancelling::contains);
        victims.sort(Comparator.comparingInt(this.inFlight::get).reversed());
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            return;
        }
        for (int i = 0; i < Math.min(toCancel, victims.size()); i++) {
            long chunk = victims.get(i);
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
            this.cancelling.add(chunk);
            this.cancelledThisCycle++;
        }
    }

    /**
     * Revokes all in-flight pregen tickets exactly once when mesh congestion first trips the gate
     * ({@code congestedStreak == CONGEST_TRIGGER_TICKS}). Revoking does not interrupt running noise
     * tasks; it only stops new layer submissions, so workers free up within 1-2 task lengths.
     * Later ticks keep the streak above the trigger, so {@link #issueTickets} stays blocked; the
     * equality check makes this fire once per congestion episode.
     */
    private void cancelInflightForMeshCongestion(MinecraftServer server) {
        if (!PregenConfig.MESH_GATE.get() || this.congestedStreak != CONGEST_TRIGGER_TICKS) {
            return;
        }
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            return;
        }
        for (Long2IntMap.Entry entry : this.inFlight.long2IntEntrySet()) {
            long chunk = entry.getLongKey();
            if (this.cancelling.contains(chunk)) {
                continue;
            }
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
            this.cancelling.add(chunk);
            this.cancelledThisCycle++;
        }
    }

    private static String meshStatus() {
        return switch (MeshPressure.current()) {
            case OK -> "ok " + MeshPressure.toBatch() + "/" + MeshPressure.freeBuffers();
            case CONGESTED -> "CLOG " + MeshPressure.toBatch() + "/" + MeshPressure.freeBuffers();
            case NO_SIGNAL -> "n/a";
        };
    }

    private void sweepCompleted(MinecraftServer server) {
        int now = server.getTickCount();
        List<Long> finished = new ArrayList<>();
        List<Long> stale = new ArrayList<>();
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        for (Long2IntMap.Entry entry : this.inFlight.long2IntEntrySet()) {
            long chunk = entry.getLongKey();
            if (level == null) {
                stale.add(chunk);
                continue;
            }
            ChunkPos pos = new ChunkPos(chunk);
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                if (!this.cancelling.contains(chunk)) {
                    level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
                }
                finished.add(chunk);
            } else if (now - entry.getIntValue() > WATCHDOG_TICKS) {
                stale.add(chunk);
            }
        }
        for (long chunk : finished) {
            this.inFlight.remove(chunk);
            this.cancelling.remove(chunk);
            this.done.add(chunk);
        }
        for (long chunk : stale) {
            this.inFlight.remove(chunk);
            this.cancelling.remove(chunk);
        }
    }

    private void issueTickets(MinecraftServer server) {
        if (this.stopping) {
            return;
        }
        if (PregenConfig.MESH_GATE.get() && this.congestedStreak >= CONGEST_TRIGGER_TICKS) {
            return;
        }
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            return;
        }
        while (this.inFlight.size() - this.cancelling.size() < this.cap) {
            Long chunk = this.queue.poll();
            if (chunk == null) {
                return;
            }
            if (this.done.contains(chunk.longValue()) || this.inFlight.containsKey(chunk.longValue()) || this.cancelling.contains(chunk.longValue())) {
                continue;
            }
            ChunkPos pos = new ChunkPos(chunk.longValue());
            level.getChunkSource().addRegionTicket(PREGEN_TICKET, pos, 0, pos);
            this.inFlight.put(chunk.longValue(), server.getTickCount());
            if (this.windowMissing > 0) {
                this.windowMissing--;
            }
        }
    }

    private void rebuildWindows(MinecraftServer server) {
        int xBehind = PregenConfig.X_BEHIND.get();
        int xAhead = PregenConfig.PREGEN_ONLY_BEHIND.get() ? 0 : PregenConfig.X_AHEAD.get();
        int zMin = PregenConfig.Z_MIN.get();
        int zMax = PregenConfig.Z_MAX.get();
        int proximityRadius = PregenConfig.PLAYER_PROXIMITY_RADIUS.get();
        Long2IntOpenHashMap targets = new Long2IntOpenHashMap();
        HashSet<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            if (player.serverLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) {
                continue;
            }
            ChunkPos current = player.chunkPosition();
            this.addWindow(targets, current.x, current.z, xBehind, xAhead, zMin, zMax);
        }
        LongOpenHashSet playerZones = new LongOpenHashSet();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) {
                continue;
            }
            ChunkPos now = player.chunkPosition();
            if (proximityRadius > 0) {
                for (int dx = -proximityRadius; dx <= proximityRadius; dx++) {
                    for (int dz = -proximityRadius; dz <= proximityRadius; dz++) {
                        playerZones.add(ChunkPos.asLong(now.x + dx, now.z + dz));
                    }
                }
            }
        }
        for (Map.Entry<UUID, Integer> entry : this.logoutAnchors.entrySet()) {
            if (online.contains(entry.getKey())) {
                continue;
            }
            this.addWindow(targets, entry.getValue(), 0, xBehind, xAhead, zMin, zMax);
        }
        targets.keySet().removeIf((long chunk) -> playerZones.contains(chunk));
        LongArrayList missing = new LongArrayList(targets.keySet().toLongArray());
        missing.removeIf((long chunk) -> this.done.contains(chunk));
        missing.removeAll(this.inFlight.keySet());
        missing.removeAll(this.cancelling);
        this.windowMissing = missing.size();
        missing.sort((a, b) -> {
            int absA = Math.abs(targets.get(a));
            int absB = Math.abs(targets.get(b));
            if (absA != absB) {
                return Integer.compare(absA, absB);
            }
            return Integer.compare(targets.get(a), targets.get(b));
        });
        List<Long> interleaved = this.interleaveSides(new ArrayList<>(missing), targets);
        int keep = Math.max(this.cap * 4, 16);
        if (interleaved.size() > keep) {
            interleaved = interleaved.subList(0, keep);
        }
        this.queue.clear();
        this.queue.addAll(interleaved);
    }

    private void addWindow(
            Long2IntOpenHashMap targets,
            int centerX,
            int centerZ,
            int xBehind,
            int xAhead,
            int zMin,
            int zMax
    ) {
        for (int dx = -xBehind; dx < xAhead; dx++) {
            for (int z = zMin; z <= zMax; z++) {
                long chunk = ChunkPos.asLong(centerX + dx, z);
                int candidate = dx;
                if (targets.containsKey(chunk)) {
                    int existing = targets.get(chunk);
                    if (Math.abs(existing) > Math.abs(candidate)) {
                        targets.put(chunk, candidate);
                    }
                } else {
                    targets.put(chunk, candidate);
                }
            }
        }
    }

    private List<Long> interleaveSides(List<Long> missing, Long2IntOpenHashMap targets) {
        List<Long> behind = new ArrayList<>();
        List<Long> ahead = new ArrayList<>();
        for (long chunk : missing) {
            if (targets.get(chunk) < 0) {
                behind.add(chunk);
            } else {
                ahead.add(chunk);
            }
        }
        List<Long> interleaved = new ArrayList<>(missing.size());
        int n = Math.max(behind.size(), ahead.size());
        for (int i = 0; i < n; i++) {
            if (i < behind.size()) {
                interleaved.add(behind.get(i));
            }
            if (i < ahead.size()) {
                interleaved.add(ahead.get(i));
            }
        }
        return interleaved;
    }
}
