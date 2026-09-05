package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.Util;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class PregenController {
    private static final int RESCAN_INTERVAL_TICKS = 20;
    private static final int TICKET_TIMEOUT_TICKS = 1200;
    private static final int WATCHDOG_TICKS = 3600;
    private static final int POOL_BACKLOG_PER_WORKER = 1;

    private static final TicketType<ChunkPos> PREGEN_TICKET =
            TicketType.create("dimblend:pregen", Comparator.comparingLong(ChunkPos::toLong), TICKET_TIMEOUT_TICKS);

    private record Key(ResourceKey<Level> dimension, long chunk) {
    }

    public record Snapshot(
            boolean enabled,
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
            int fastMoving,
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
                    + "  fast " + this.fastMoving
                    + "  cancel " + this.cancelledThisCycle
                    + "  logout " + this.anchors);
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

    private final ConcurrentLinkedQueue<Key> queue = new ConcurrentLinkedQueue<>();
    private final Set<Key> done = ConcurrentHashMap.newKeySet();
    private final Map<Key, Integer> inFlight = new ConcurrentHashMap<>();
    private final Set<Key> cancelling = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> logoutAnchors = new ConcurrentHashMap<>();
    private final Map<UUID, ChunkPos> lastPlayerChunk = new HashMap<>();
    private final Set<UUID> fastMovingPlayers = new HashSet<>();
    private volatile int cap;
    private volatile boolean stopping;
    private int tickCounter;
    private int healthyStreak;
    private int windowMissing;
    private int backlogStreak;
    private int cancelledThisCycle;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.stopping || !PregenConfig.ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        this.cancelledThisCycle = 0;
        this.sweepCompleted(server);
        this.cancelInflightDueToBacklog(server);
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
        this.queue.clear();
        MinecraftServer server = event.getServer();
        for (Key key : List.copyOf(this.inFlight.keySet())) {
            if (this.cancelling.contains(key)) {
                continue;
            }
            ServerLevel level = server.getLevel(key.dimension());
            if (level != null) {
                ChunkPos pos = new ChunkPos(key.chunk());
                level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
            }
        }
        this.inFlight.clear();
        this.cancelling.clear();
        this.done.clear();
        this.logoutAnchors.clear();
        this.lastPlayerChunk.clear();
        this.fastMovingPlayers.clear();
        this.cap = PregenConfig.MIN_IN_FLIGHT.get();
        this.tickCounter = 0;
        this.healthyStreak = 0;
        this.windowMissing = 0;
        this.backlogStreak = 0;
        this.cancelledThisCycle = 0;
        this.stopping = false;
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
                PregenConfig.ENABLED.get(),
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
                this.fastMovingPlayers.size(),
                this.cancelledThisCycle,
                averageTickMs(server),
                xBehind,
                xAhead,
                zMin,
                zMax
        );
    }

    private WindowStats windowStats(MinecraftServer server, int xBehind, int xAhead, int zMin, int zMax) {
        Map<Key, Integer> side = new HashMap<>();
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
        for (Map.Entry<Key, Integer> entry : side.entrySet()) {
            boolean finished = this.done.contains(entry.getKey());
            if (finished) {
                done++;
            }
            if (entry.getValue() < 0) {
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

    private void adjustCap(MinecraftServer server) {
        int min = Math.min(PregenConfig.MIN_IN_FLIGHT.get(), PregenConfig.MAX_IN_FLIGHT.get());
        int max = Math.max(min, PregenConfig.MAX_IN_FLIGHT.get());
        if (this.cap == 0) {
            this.cap = min;
        } else if (this.cap > max) {
            this.cap = max;
        }

        boolean backlogged = worldgenPoolBacklogged();
        boolean fast = !this.fastMovingPlayers.isEmpty();
        double avgMs = averageTickMs(server);

        if (avgMs > PregenConfig.BRAKE_TICK_MS.get() || backlogged || fast) {
            this.cap = Math.max(1, this.cap / 2);
            if (backlogged || fast) {
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
        List<Key> victims = new ArrayList<>(this.inFlight.keySet());
        victims.removeIf(this.cancelling::contains);
        victims.sort(Comparator.comparingInt(this.inFlight::get).reversed());
        for (int i = 0; i < Math.min(toCancel, victims.size()); i++) {
            Key key = victims.get(i);
            ServerLevel level = server.getLevel(key.dimension());
            if (level != null) {
                ChunkPos pos = new ChunkPos(key.chunk());
                level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
            }
            this.cancelling.add(key);
            this.cancelledThisCycle++;
        }
    }

    private void sweepCompleted(MinecraftServer server) {
        int now = server.getTickCount();
        List<Key> finished = new ArrayList<>();
        List<Key> stale = new ArrayList<>();
        for (Map.Entry<Key, Integer> entry : this.inFlight.entrySet()) {
            Key key = entry.getKey();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                stale.add(key);
                continue;
            }
            ChunkPos pos = new ChunkPos(key.chunk());
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                if (!this.cancelling.contains(key)) {
                    level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
                }
                finished.add(key);
            } else if (now - entry.getValue() > WATCHDOG_TICKS) {
                stale.add(key);
            }
        }
        for (Key key : finished) {
            this.inFlight.remove(key);
            this.cancelling.remove(key);
            this.done.add(key);
        }
        for (Key key : stale) {
            this.inFlight.remove(key);
            this.cancelling.remove(key);
        }
    }

    private void issueTickets(MinecraftServer server) {
        if (this.stopping) {
            return;
        }
        while (this.inFlight.size() - this.cancelling.size() < this.cap) {
            Key key = this.queue.poll();
            if (key == null) {
                return;
            }
            if (this.done.contains(key) || this.inFlight.containsKey(key) || this.cancelling.contains(key)) {
                continue;
            }
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                continue;
            }
            ChunkPos pos = new ChunkPos(key.chunk());
            level.getChunkSource().addRegionTicket(PREGEN_TICKET, pos, 0, pos);
            this.inFlight.put(key, server.getTickCount());
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
        Map<Key, Integer> targets = new HashMap<>();
        HashSet<UUID> online = new HashSet<>();
        LongSet playerZones = new LongOpenHashSet();
        this.fastMovingPlayers.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            online.add(id);
            if (player.serverLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) {
                this.lastPlayerChunk.remove(id);
                continue;
            }
            ChunkPos now = player.chunkPosition();
            ChunkPos before = this.lastPlayerChunk.put(id, now);
            if (before != null && now.getChessboardDistance(before) > PregenConfig.MOVING_CHUNK_THRESHOLD.get()) {
                this.fastMovingPlayers.add(id);
            }
            if (proximityRadius > 0) {
                for (int dx = -proximityRadius; dx <= proximityRadius; dx++) {
                    for (int dz = -proximityRadius; dz <= proximityRadius; dz++) {
                        playerZones.add(ChunkPos.asLong(now.x + dx, now.z + dz));
                    }
                }
            }
            this.addWindow(targets, now.x, now.z, xBehind, xAhead, zMin, zMax);
        }
        this.lastPlayerChunk.keySet().retainAll(online);
        for (Map.Entry<UUID, Integer> entry : this.logoutAnchors.entrySet()) {
            if (online.contains(entry.getKey())) {
                continue;
            }
            this.addWindow(targets, entry.getValue(), 0, xBehind, xAhead, zMin, zMax);
        }
        targets.keySet().removeIf(key -> playerZones.contains(key.chunk()));
        List<Key> missing = new ArrayList<>(targets.keySet());
        missing.removeIf(this.done::contains);
        missing.removeAll(this.inFlight.keySet());
        missing.removeAll(this.cancelling);
        this.windowMissing = missing.size();
        missing.sort(Comparator
                .comparingInt((Key key) -> Math.abs(targets.get(key)))
                .thenComparingInt(key -> targets.get(key)));
        List<Key> interleaved = this.interleaveSides(missing, targets);
        int keep = Math.max(this.cap * 4, 16);
        if (interleaved.size() > keep) {
            interleaved = interleaved.subList(0, keep);
        }
        this.queue.clear();
        this.queue.addAll(interleaved);
    }

    private void addWindow(
            Map<Key, Integer> targets,
            int centerX,
            int centerZ,
            int xBehind,
            int xAhead,
            int zMin,
            int zMax
    ) {
        ResourceKey<Level> dimension = DimBlendRegistries.ROTATING_LEVEL;
        for (int dx = -xBehind; dx < xAhead; dx++) {
            for (int z = zMin; z <= zMax; z++) {
                Key key = new Key(dimension, ChunkPos.asLong(centerX + dx, z));
                targets.merge(key, dx, (a, b) -> Math.abs(a) <= Math.abs(b) ? a : b);
            }
        }
    }

    private List<Key> interleaveSides(List<Key> missing, Map<Key, Integer> targets) {
        List<Key> behind = new ArrayList<>();
        List<Key> ahead = new ArrayList<>();
        for (Key key : missing) {
            if (targets.get(key) < 0) {
                behind.add(key);
            } else {
                ahead.add(key);
            }
        }
        List<Key> interleaved = new ArrayList<>(missing.size());
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
