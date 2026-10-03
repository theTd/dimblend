package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import dimblend.mixin.DistanceManagerAccessor;
import dimblend.mixin.ChunkMapAccessor;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.lang.management.ManagementFactory;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
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
    private static final int HOLD_RADIUS = 8; // matches ChunkPyramid STRUCTURE_STARTS dependency radius
    private static final int HOLD_CAP = 32;
    private static final int FOREIGN_SCAN_INTERVAL = 10;
    private static final TicketType<ChunkPos> PREGEN_TICKET =
            TicketType.create("dimblend:pregen", Comparator.comparingLong(ChunkPos::toLong));

    public record Snapshot(
            boolean enabled,
            String overrideMarker,
            int window,
            int done,
            int inFlight,
            int foreign,
            boolean yielding,
            int cancelling,
            int held,
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
            String admission,
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
                    + "  gate " + this.admission
                    + "  logout " + this.anchors
                    + "  hold " + this.held
                    + "  ext " + this.foreign
                    + (this.yielding ? "  YIELD" : "")
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
    /** Completed chunks whose pregen ticket stays registered until radius-8 neighbors settle. */
    private final LongLinkedOpenHashSet heldTickets = new LongLinkedOpenHashSet();
    /** Post-proximity window membership from the last rescan; settle oracle for held tickets. */
    private LongOpenHashSet windowTargets = new LongOpenHashSet();
    private final Map<UUID, Integer> logoutAnchors = new ConcurrentHashMap<>();
    private volatile int cap;
    private volatile boolean stopping;
    private volatile Boolean pregenOverride;
    private int tickCounter;
    private int healthyStreak;
    private int windowMissing;
    private int cancelledThisCycle;
    private final PregenAdmission admission = new PregenAdmission();
    private long lastCpuSample;
    private double cpuLoad = Double.NaN;
    private int nextForeignScanTick;
    private boolean forceForeignScan = true;
    private int scannedViewDistance = -1;
    private final Map<UUID, PlayerView> playerViews = new java.util.HashMap<>();
    private record PlayerView(ServerLevel level, long chunk) {}

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.stopping) {
            return;
        }
        MinecraftServer server = event.getServer();
        this.cancelledThisCycle = 0;
        this.sweepCompleted(server);
        if (!pregenEffective()) {
            this.pause(server, PregenAdmission.Reason.DISABLED);
            return;
        }
        if (!event.hasTime()) {
            this.pause(server, PregenAdmission.Reason.TICKS);
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        boolean clientReady = server.isDedicatedServer() || MeshPressure.current() == MeshPressure.Signal.OK;
        double tickLimit = Math.min(PregenConfig.OK_TICK_MS.get(), PregenConfig.BRAKE_TICK_MS.get());
        PregenAdmission.Reason reason = this.admission.update(now, this.systemCpuLoad(now),
                PregenConfig.MAX_SYSTEM_CPU_LOAD.get(), clientReady, worldgenPoolBacklogged(),
                averageTickMs(server), lastTickMs(server), tickLimit, PregenConfig.RECOVERY_SECONDS.get() * 1000L);
        if (reason != PregenAdmission.Reason.READY) {
            this.pause(server, reason);
            return;
        }
        this.updateForeignYield(server);
        if (this.yielding) {
            this.pause(server, PregenAdmission.Reason.FOREIGN);
            return;
        }
        for (long chunk : this.inFlight.keySet()) {
            if (!this.cancelling.contains(chunk) && !outsidePlayerView(server, chunk)) {
                this.pause(server, PregenAdmission.Reason.PLAYER_NEAR);
                return;
            }
        }
        this.adjustCap(server);
        if (++this.tickCounter >= RESCAN_INTERVAL_TICKS) {
            this.tickCounter = 0;
            this.rebuildWindows(server);
        }
        this.issueTickets(server);
    }

    private void pause(MinecraftServer server, PregenAdmission.Reason reason) {
        this.cap = 0;
        this.healthyStreak = 0;
        this.queue.clear();
        this.tickCounter = RESCAN_INTERVAL_TICKS;
        this.forceForeignScan = true;
        if (reason != PregenAdmission.Reason.RECOVERING) this.admission.block(reason);
        this.cancelInflightTickets(server.getLevel(DimBlendRegistries.ROTATING_LEVEL));
    }

    private double systemCpuLoad(long now) {
        if (this.lastCpuSample != 0 && now - this.lastCpuSample < 1000) return this.cpuLoad;
        this.lastCpuSample = now;
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            this.cpuLoad = bean instanceof com.sun.management.OperatingSystemMXBean extended
                    ? extended.getCpuLoad() : Double.NaN;
        } catch (RuntimeException | LinkageError unavailable) {
            this.cpuLoad = Double.NaN;
        }
        return this.cpuLoad;
    }

    /** Distinct unloaded chunks demanded by players or non-pregen tickets across all levels. */
    private int foreignPending;
    private boolean yielding;

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
        this.heldTickets.clear();
        this.windowTargets = new LongOpenHashSet();
        this.logoutAnchors.clear();
        this.cap = 0;
        this.tickCounter = 0;
        this.healthyStreak = 0;
        this.windowMissing = 0;
        this.cancelledThisCycle = 0;
        this.foreignPending = 0;
        this.yielding = false;
        this.admission.block(PregenAdmission.Reason.RECOVERING);
        this.cpuLoad = Double.NaN;
        this.lastCpuSample = 0;
        this.forceForeignScan = true;
        this.playerViews.clear();
        this.scannedViewDistance = -1;
        this.nextForeignScanTick = 0;
        this.stopping = false;
    }

    /** Runtime on/off/auto switch; null means follow the config value. */
    public boolean pregenEffective() {
        Boolean override = this.pregenOverride;
        return override != null ? override : PregenConfig.ENABLED.get();
    }

    /**
     * Sets the runtime override. Turning pregen off revokes all non-cancelling in-flight and
     * held tickets so generation stops and resident chunks are released immediately.
     */
    public void setPregenOverride(Boolean override) {
        this.pregenOverride = override;
        if (override != null && !override) {
            this.cap = 0;
            this.admission.block(PregenAdmission.Reason.DISABLED);
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
     * Revokes every non-cancelling in-flight ticket and every held ticket. In-flight chunks are
     * marked cancelling so {@link #sweepCompleted(MinecraftServer)} will not remove their tickets
     * a second time once they arrive; held chunks already completed, so their tickets are simply
     * released.
     */
    private void cancelInflightTickets(ServerLevel level) {
        if (level == null) {
            return;
        }
        this.cancelActiveInflight(level);
        for (long chunk : this.heldTickets) {
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
        }
        this.heldTickets.clear();
    }

    /**
     * Revokes every non-cancelling in-flight ticket, marking it cancelling so
     * {@link #sweepCompleted(MinecraftServer)} will not remove its ticket twice once it arrives.
     */
    private void cancelActiveInflight(ServerLevel level) {
        for (long chunk : this.inFlight.keySet()) {
            if (this.cancelling.contains(chunk)) {
                continue;
            }
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
            this.cancelling.add(chunk);
            this.inFlight.put(chunk, level.getServer().getTickCount());
            this.cancelledThisCycle++;
        }
    }

    /**
     * Rescans immediately on player movement and otherwise twice a second, only with spare capacity.
     */
    private void updateForeignYield(MinecraftServer server) {
        boolean changed = this.playerViewsChanged(server);
        if (this.forceForeignScan || changed || server.getTickCount() >= this.nextForeignScanTick) {
            this.foreignPending = this.scanForeignPending(server);
            this.nextForeignScanTick = server.getTickCount() + FOREIGN_SCAN_INTERVAL;
            this.forceForeignScan = false;
        }
        this.yielding = this.foreignPending > 0;
    }

    private boolean playerViewsChanged(MinecraftServer server) {
        boolean changed = this.scannedViewDistance != server.getPlayerList().getViewDistance()
                || this.playerViews.size() != server.getPlayerList().getPlayers().size();
        this.scannedViewDistance = server.getPlayerList().getViewDistance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerView old = this.playerViews.get(player.getUUID());
            long chunk = player.chunkPosition().toLong();
            if (old == null || old.level() != player.serverLevel() || old.chunk() != chunk) {
                this.playerViews.put(player.getUUID(), new PlayerView(player.serverLevel(), chunk));
                changed = true;
            }
        }
        if (changed) this.playerViews.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        return changed;
    }

    /** Counts distinct unloaded chunks demanded by players or non-pregen tickets across all levels. */
    private int scanForeignPending(MinecraftServer server) {
        int fullLevel = ChunkLevel.byStatus(FullChunkStatus.FULL);
        int vd = server.getPlayerList().getViewDistance();
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            LongOpenHashSet pending = new LongOpenHashSet();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.serverLevel() != level) {
                    continue;
                }
                ChunkPos pos = player.chunkPosition();
                for (int dx = -vd; dx <= vd; dx++) {
                    for (int dz = -vd; dz <= vd; dz++) {
                        if (!player.getChunkTrackingView().isInViewDistance(pos.x + dx, pos.z + dz)) continue;
                        if (level.getChunkSource().getChunkNow(pos.x + dx, pos.z + dz) == null) {
                            pending.add(ChunkPos.asLong(pos.x + dx, pos.z + dz));
                        }
                    }
                }
            }
            Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets =
                    ((DistanceManagerAccessor) level.getChunkSource().chunkMap.getDistanceManager()).dimblend$getTickets();
            for (Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry : tickets.long2ObjectEntrySet()) {
                long chunk = entry.getLongKey();
                boolean foreign = false;
                for (Ticket<?> ticket : entry.getValue()) {
                    if (ticket.getType() != PREGEN_TICKET && ticket.getTicketLevel() <= fullLevel) {
                        foreign = true;
                        break;
                    }
                }
                if (foreign && level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null) {
                    pending.add(chunk);
                }
            }
            total += pending.size();
        }
        return total;
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

    /**
     * Forgets FULL progress of a region whose files were just deleted, so a revisit drives the
     * regenerated chunks again instead of trusting the stale {@code done} entry. Also bounds
     * {@code done}, which otherwise grows with every chunk pregen ever finished.
     */
    public void forgetRegion(int regionX, int regionZ) {
        int minX = regionX << 5;
        int minZ = regionZ << 5;
        for (int dx = 0; dx < 32; dx++) {
            for (int dz = 0; dz < 32; dz++) {
                this.done.remove(ChunkPos.asLong(minX + dx, minZ + dz));
            }
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
                this.foreignPending,
                this.yielding,
                this.cancelling.size(),
                this.heldTickets.size(),
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
                this.admission.reason().name().toLowerCase(java.util.Locale.ROOT),
                this.cancelledThisCycle,
                averageTickMs(server),
                xBehind,
                xAhead,
                zMin,
                zMax
        );
    }

    /** Age in ticks of the oldest in-flight ticket, or -1 when nothing is in flight. */
    public int oldestInFlightAge(int nowTick) {
        boolean any = false;
        int oldest = 0;
        for (int issueTick : this.inFlight.values()) {
            if (!any || issueTick - nowTick < oldest - nowTick) {
                oldest = issueTick;
                any = true;
            }
        }
        return any ? nowTick - oldest : -1;
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
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            max = Math.min(max, PregenConfig.ONLINE_MAX_IN_FLIGHT.get());
            min = Math.min(min, max);
        }
        if (this.cap == 0) {
            this.cap = min;
        } else if (this.cap > max) {
            this.cap = max;
        }

        this.healthyStreak++;
        if (this.healthyStreak < PregenConfig.RAISE_STREAK_TICKS.get()) {
            return;
        }
        this.healthyStreak = 0;
        if (this.cap >= max || !this.hasDemand()) {
            return;
        }
        this.cap = this.cap < min ? min : this.cap + 1;
    }

    private boolean hasDemand() {
        int active = this.inFlight.size();
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
            return true;
        }
        return PregenAdmission.poolBusy(pool.getParallelism(), pool.getActiveThreadCount(),
                pool.getQueuedSubmissionCount(), pool.getQueuedTaskCount());
    }

    private static double lastTickMs(MinecraftServer server) {
        long[] times = server.getTickTimesNanos();
        return times[Math.floorMod(server.getTickCount() - 1, times.length)] / 1_000_000.0;
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
            if (this.cancelling.contains(chunk)) {
                var access = (ChunkMapAccessor) level.getChunkSource().chunkMap;
                var holder = access.dimblend$getUpdatingChunkMap().get(chunk);
                if (holder == null) holder = access.dimblend$getPendingUnloads().get(chunk);
                if (now > entry.getIntValue() && (holder == null || holder.getGenerationRefCount() == 0)) {
                    stale.add(chunk);
                }
                continue;
            }
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                var holder = ((ChunkMapAccessor) level.getChunkSource().chunkMap).dimblend$getUpdatingChunkMap().get(chunk);
                if (holder != null && holder.getGenerationRefCount() > 0) continue;
                if (neighborsSettled(chunk)) {
                    level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
                } else {
                    this.heldTickets.add(chunk);
                }
                finished.add(chunk);
            } else if (now - entry.getIntValue() > WATCHDOG_TICKS) {
                level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
                this.cancelling.add(chunk);
                entry.setValue(now);
                this.cancelledThisCycle++;
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
        if (!finished.isEmpty() || now % FOREIGN_SCAN_INTERVAL == 0) this.releaseHeldTickets(level);
    }

    /**
     * A completed chunk's radius-8 neighborhood is settled when every neighbor is either already
     * done or outside the current pregen window (membership is refreshed by
     * {@link #rebuildWindows(MinecraftServer)}).
     */
    private boolean neighborsSettled(long chunk) {
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        for (int dx = -HOLD_RADIUS; dx <= HOLD_RADIUS; dx++) {
            for (int dz = -HOLD_RADIUS; dz <= HOLD_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long n = ChunkPos.asLong(x + dx, z + dz);
                if (this.done.contains(n) || !this.windowTargets.contains(n)) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Releases held tickets whose neighborhood has settled (or whose chunk is no longer
     * resident, e.g. an external removal unloaded it) and enforces {@link #HOLD_CAP} by dropping
     * the oldest holds. {@code removeRegionTicket} is a no-op when the ticket is already gone.
     */
    private void releaseHeldTickets(ServerLevel level) {
        if (level == null) {
            this.heldTickets.clear();
            return;
        }
        LongArrayList release = new LongArrayList();
        for (long chunk : this.heldTickets) {
            ChunkPos pos = new ChunkPos(chunk);
            if (neighborsSettled(chunk) || level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
                release.add(chunk);
            }
        }
        for (long chunk : release) {
            this.heldTickets.remove(chunk);
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
        }
        int holdLimit = Math.min(HOLD_CAP, Math.max(1, this.cap) * 4);
        while (this.heldTickets.size() > holdLimit) {
            long chunk = this.heldTickets.removeFirstLong();
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
        }
    }

    private void issueTickets(MinecraftServer server) {
        if (this.stopping) {
            return;
        }
        if (this.yielding || this.admission.reason() != PregenAdmission.Reason.READY) {
            return;
        }
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            return;
        }
        while (PregenAdmission.canIssue(this.inFlight.size(), this.cancelling.size(), this.cap)) {
            Long chunk = this.queue.poll();
            if (chunk == null) {
                return;
            }
            if (this.done.contains(chunk.longValue()) || this.inFlight.containsKey(chunk.longValue()) || this.cancelling.contains(chunk.longValue())) {
                continue;
            }
            ChunkPos pos = new ChunkPos(chunk.longValue());
            if (!outsidePlayerView(server, chunk)) continue;
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                this.done.add(chunk);
                continue;
            }
            level.getChunkSource().addRegionTicket(PREGEN_TICKET, pos, 0, pos);
            this.inFlight.put(chunk.longValue(), server.getTickCount());
            if (this.windowMissing > 0) {
                this.windowMissing--;
            }
        }
    }

    private static boolean outsidePlayerView(MinecraftServer server, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel().dimension() != DimBlendRegistries.ROTATING_LEVEL) continue;
            ChunkPos pos = player.chunkPosition();
            if (!PregenAdmission.outsidePlayerView(x, z, pos.x, pos.z,
                    server.getPlayerList().getViewDistance(), ChunkLevel.RADIUS_AROUND_FULL_CHUNK,
                    PregenConfig.PLAYER_PROXIMITY_RADIUS.get())) {
                return false;
            }
        }
        return true;
    }

    private void rebuildWindows(MinecraftServer server) {
        int xBehind = PregenConfig.X_BEHIND.get();
        int xAhead = PregenConfig.PREGEN_ONLY_BEHIND.get() ? 0 : PregenConfig.X_AHEAD.get();
        int zMin = PregenConfig.Z_MIN.get();
        int zMax = PregenConfig.Z_MAX.get();
        int proximityRadius = Math.max(PregenConfig.PLAYER_PROXIMITY_RADIUS.get(),
                server.getPlayerList().getViewDistance() + ChunkLevel.RADIUS_AROUND_FULL_CHUNK);
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
        // Store the post-proximity window so held-ticket settle checks match the live window.
        this.windowTargets = new LongOpenHashSet(targets.keySet());
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
