package dimblend.worldgen;

import com.mojang.logging.LogUtils;
import dimblend.DimBlend;
import dimblend.DimBlendRegistries;
import dimblend.mixin.ChunkMapAccessor;
import dimblend.mixin.DistanceManagerAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Engine-side chunk-system monitor for the rotating level.
 *
 * <p>Everything on the bossbar and in the stall log comes from the vanilla chunk system:
 * visible holders, unresolved generation futures, the ticket-level priority sorter, the
 * main-thread executor backlog, unload queues, tick cost, and the shared background pool
 * the worldgen mailboxes run on. The only mod-side line, labeled {@code pregen}, is a
 * one-line context so pregen ticket pressure can be told apart from engine pressure.
 *
 * <p>Root stall indicator: a per-chunk ledger of unresolved generation futures. A holder
 * {@code getAllFutures()} entry that stays incomplete across samples longer than
 * {@link #STUCK_TICKS} marks the chunk as stuck. That is the direct symptom of a
 * generation step whose future never completes; in particular the fatal-exception path
 * leaves the step future pending and the generation ref count claimed, so the chunk can
 * never unload.
 *
 * <p>Stall detection and logging always run. The bossbar auto-subscribes every online
 * permission-2 player and can be hidden per player with {@code /dimblend watch off}.
 */
public final class ChunkGenMonitor {
    /** Sampling cadence, in server ticks. */
    private static final int SAMPLE_TICKS = 20;
    /** An unresolved future older than this, in ticks, is stuck. */
    private static final int STUCK_TICKS = 600;
    /** Minimum tick gap between two stall log emissions. */
    private static final int LOG_THROTTLE_TICKS = 1200;
    /** Ledger slots per chunk; ChunkStatus.FULL has the highest status index. */
    private static final int STATUS_SLOTS = ChunkStatus.FULL.getIndex() + 1;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Per-chunk aging ledger. A slot's stamp is only meaningful while the pending future
     * it was recorded for is still the one in that slot; a different future identity
     * (the slot was cleared to null by a ticket downgrade and re-created between scans)
     * restarts aging from zero. Stamps are int tick counts: the server tick counter
     * wraps at 2^31, and both stamp and age are plain int subtractions whose signed
     * difference is correct for real ages far below that horizon.
     */
    private static final class Ledger {
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[STATUS_SLOTS];
        final int[] stamps = new int[STATUS_SLOTS];
    }

    /** First-seen tick per pending (chunk, status) pair, indexed by status index. */
    private final Long2ObjectMap<Ledger> pending = new Long2ObjectOpenHashMap<>();
    /** Players who ran {@code watch off}; excluded from auto-subscription until they opt back in. */
    private final Set<UUID> optedOut = new HashSet<>();
    /** Scan scratch of currently visible chunk keys; server thread only. */
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private ServerBossEvent bossbar;
    private int sampleCounter;
    /** Ticks since the last stall log; starts saturated so the first stall logs immediately. */
    private int ticksSinceLog = LOG_THROTTLE_TICKS;
    /**
     * Tick of the last scan, for normalizing step completions to per-second. Never reset by
     * {@code /dimblend watch dump}; a dump mid-window does not shorten the next sample.
     */
    private int lastScanTick = -1;

    /** Re-subscribes a player who previously ran {@code /dimblend watch off}. */
    public void watchOn(ServerPlayer player) {
        this.optedOut.remove(player.getUUID());
        if (this.bossbar != null) {
            this.bossbar.addPlayer(player);
        }
    }

    /** Hides the bossbar for this player until they opt back in. */
    public void watchOff(ServerPlayer player) {
        this.optedOut.add(player.getUUID());
        if (this.bossbar != null) {
            this.bossbar.removePlayer(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.ticksSinceLog < LOG_THROTTLE_TICKS) {
            this.ticksSinceLog++;
        }
        if (++this.sampleCounter < SAMPLE_TICKS) {
            return;
        }
        this.sampleCounter = 0;
        this.sample(event.getServer());
    }

    /** Engine-side snapshot of the rotating level's chunk system, one sample. */
    private record EngineStats(
            int visibleHolders,
            int pendingFutures,
            List<StuckEntry> stuck,
            int ratePerSample,
            boolean sorterHasWork,
            int mainPending,
            int unloadBacklog,
            int ticketsToRelease,
            float tickMs
    ) {
    }

    private void sample(MinecraftServer server) {
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            return;
        }
        EngineStats stats = this.collect(server, level);
        this.updateBossbar(server, stats);
        if (!stats.stuck().isEmpty() && this.ticksSinceLog >= LOG_THROTTLE_TICKS) {
            this.ticksSinceLog = 0;
            this.logStall(level, stats);
        }
    }

    private EngineStats collect(MinecraftServer server, ServerLevel level) {
        int now = server.getTickCount();
        List<StuckEntry> stuck = new ArrayList<>();
        int visible = 0;
        int pendCount = 0;
        this.seen.clear();
        int completedSteps = 0;
        for (ChunkHolder holder : ((ChunkMapAccessor) level.getChunkSource().chunkMap).dimblend$getChunks()) {
            visible++;
            long chunk = holder.getPos().toLong();
            this.seen.add(chunk);
            Ledger ledger = this.pending.get(chunk);
            boolean anyPending = false;
            for (com.mojang.datafixers.util.Pair<ChunkStatus, CompletableFuture<ChunkResult<ChunkAccess>>> pair
                    : holder.getAllFutures()) {
                CompletableFuture<ChunkResult<ChunkAccess>> future = pair.getSecond();
                int slot = pair.getFirst().getIndex();
                boolean trackable = slot >= 0 && slot < STATUS_SLOTS;
                if (future != null && !future.isDone()) {
                    anyPending = true;
                    if (ledger == null) {
                        ledger = new Ledger();
                        this.pending.put(chunk, ledger);
                    }
                    if (ledger.futures[slot] != future) {
                        ledger.futures[slot] = future;
                        ledger.stamps[slot] = now;
                    } else if (now - ledger.stamps[slot] > STUCK_TICKS) {
                        stuck.add(new StuckEntry(
                                chunk,
                                pair.getFirst(),
                                now - ledger.stamps[slot],
                                holder.getGenerationRefCount(),
                                holder.getQueueLevel()
                        ));
                    }
                } else if (trackable && ledger != null && ledger.futures[slot] != null) {
                    // The tracked future resolved (or the slot was cleared by a downgrade)
                    // since the last scan: count the step completion, then forget the slot
                    // so a re-pending status restarts aging.
                    completedSteps++;
                    ledger.futures[slot] = null;
                    ledger.stamps[slot] = 0;
                }
            }
            if (anyPending) {
                pendCount++;
            }
            if (!anyPending && ledger != null) {
                this.pending.remove(chunk);
            }
        }
        this.pending.keySet().removeIf((long key) -> !this.seen.contains(key));

        // Normalize step completions over the actual elapsed ticks since the last scan
        // (rounding up), so a mid-window dump does not distort the next scheduled rate.
        int elapsed = this.lastScanTick < 0 ? SAMPLE_TICKS : Math.max(1, now - this.lastScanTick);
        this.lastScanTick = now;
        int rate = (completedSteps * 20 + elapsed - 1) / elapsed;

        ChunkMapAccessor chunkMap = (ChunkMapAccessor) level.getChunkSource().chunkMap;
        ChunkTaskPriorityQueueSorter sorter = chunkMap.dimblend$getQueueSorter();
        DistanceManagerAccessor distanceManager =
                (DistanceManagerAccessor) level.getChunkSource().chunkMap.getDistanceManager();
        return new EngineStats(
                visible,
                pendCount,
                stuck,
                rate,
                sorter != null && sorter.hasWork(),
                server.getPendingTasksCount(),
                chunkMap.dimblend$getPendingUnloads().size(),
                distanceManager.dimblend$getTicketsToRelease().size(),
                server.getCurrentSmoothedTickTime()
        );
    }

    private void updateBossbar(MinecraftServer server, EngineStats stats) {
        if (this.bossbar == null) {
            this.bossbar = new ServerBossEvent(
                    Component.literal("chunkgen"),
                    BossEvent.BossBarColor.GREEN,
                    BossEvent.BossBarOverlay.NOTCHED_10
            );
            this.bossbar.setDarkenScreen(false);
            this.bossbar.setPlayBossMusic(false);
            this.bossbar.setCreateWorldFog(false);
        }
        this.syncViewers(server);
        if (this.bossbar.getPlayers().isEmpty()) {
            return;
        }
        this.bossbar.setColor(this.pickColor(stats));
        this.bossbar.setName(this.bossbarName(stats));
        this.bossbar.setProgress(this.bossbarProgress(stats));
    }

    /** Subscribes every online permission-2 player who has not opted out. */
    private void syncViewers(MinecraftServer server) {
        Set<ServerPlayer> wanted = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2) && !this.optedOut.contains(player.getUUID())) {
                wanted.add(player);
            }
        }
        for (ServerPlayer viewer : new ArrayList<>(this.bossbar.getPlayers())) {
            if (!wanted.contains(viewer)) {
                this.bossbar.removePlayer(viewer);
            }
        }
        for (ServerPlayer player : wanted) {
            this.bossbar.addPlayer(player);
        }
    }

    private BossEvent.BossBarColor pickColor(EngineStats stats) {
        if (!stats.stuck().isEmpty()) {
            return BossEvent.BossBarColor.RED;
        }
        if (stats.pendingFutures() > 0 && stats.ratePerSample() == 0) {
            return BossEvent.BossBarColor.YELLOW;
        }
        return BossEvent.BossBarColor.GREEN;
    }

    private Component bossbarName(EngineStats stats) {
        StringBuilder name = new StringBuilder("chunkgen ")
                .append(stats.ratePerSample()).append("/s")
                .append("  pend ").append(stats.pendingFutures())
                .append("  v ").append(stats.visibleHolders())
                .append("  sort ").append(stats.sorterHasWork() ? "Y" : "N")
                .append("  main ").append(stats.mainPending())
                .append("  ").append(String.format("%.0f", stats.tickMs())).append("ms");
        if (stats.unloadBacklog() > 0) {
            name.append("  unload ").append(stats.unloadBacklog());
        }
        if (stats.ticketsToRelease() > 0) {
            name.append("  trel ").append(stats.ticketsToRelease());
        }
        if (!stats.stuck().isEmpty()) {
            name.append("  STUCK ").append(stats.stuck().size()).append(": ").append(stats.stuck().get(0).render());
        }
        return Component.literal(name.toString());
    }

    /** Fraction of visible holders with no unresolved generation future. */
    private float bossbarProgress(EngineStats stats) {
        if (stats.visibleHolders() <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, 1.0F - stats.pendingFutures() / (float) stats.visibleHolders());
    }

    /**
     * Runs a fresh scan and returns a report for {@code /dimblend watch}. The scan mutates
     * the ledger exactly like a scheduled sample, so the printed ages are consistent with it.
     */
    public List<String> dump(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            lines.add("chunkgen: rotating level absent");
            return lines;
        }
        EngineStats stats = this.collect(server, level);
        lines.add("chunkgen  rate " + stats.ratePerSample() + "/s"
                + "  pend " + stats.pendingFutures()
                + "  stuck " + stats.stuck().size()
                + "  ledger " + this.pending.size());
        lines.add("v " + stats.visibleHolders()
                + "  sort " + (stats.sorterHasWork() ? "Y" : "N")
                + "  main " + stats.mainPending()
                + "  unload " + stats.unloadBacklog()
                + "  trel " + stats.ticketsToRelease()
                + "  " + String.format("%.0f", stats.tickMs()) + "ms"
                + "  pool " + poolStats());
        PregenController.Snapshot pregen = DimBlend.pregen().snapshot(server);
        lines.add("pregen  fly " + pregen.inFlight()
                + "  cap " + pregen.cap() + "/" + pregen.maxInFlight()
                + "  yield " + pregen.yielding()
                + "  foreign " + pregen.foreign()
                + "  hold " + pregen.held()
                + (DimBlend.pregen().oldestInFlightAge(server.getTickCount()) >= 0
                        ? "  oldest " + DimBlend.pregen().oldestInFlightAge(server.getTickCount()) + "t"
                        : ""));
        lines.addAll(stallDetail(tickets(level), stats.stuck()));
        return lines;
    }

    /** One stuck future with its aging, for logging and bossbar display. */
    private record StuckEntry(long chunk, ChunkStatus status, int ageTicks, int refCount, int queueLevel) {
        private String render() {
            return ChunkPos.getX(this.chunk) + "," + ChunkPos.getZ(this.chunk)
                    + " " + this.status.getName()
                    + " " + this.ageTicks / 20 + "s"
                    + " ref " + this.refCount
                    + " q " + this.queueLevel;
        }
    }

    private void logStall(ServerLevel level, EngineStats stats) {
        LOGGER.warn("chunkgen stall on {} ({} stuck, {} pend): rate {}/s v {} sort {} main {} unload {} trel {}"
                        + " | tick {}ms | pool {}",
                level.dimension().location(),
                stats.stuck().size(),
                stats.pendingFutures(),
                stats.ratePerSample(),
                stats.visibleHolders(),
                stats.sorterHasWork() ? "Y" : "N",
                stats.mainPending(),
                stats.unloadBacklog(),
                stats.ticketsToRelease(),
                String.format("%.0f", stats.tickMs()),
                poolStats());
        PregenController.Snapshot pregen = DimBlend.pregen().snapshot(level.getServer());
        LOGGER.warn("pregen context: fly {} cap {}/{} yield {} foreign {} hold {}",
                pregen.inFlight(),
                pregen.cap(),
                pregen.maxInFlight(),
                pregen.yielding(),
                pregen.foreign(),
                pregen.held());
        for (String line : stallDetail(tickets(level), stats.stuck())) {
            LOGGER.warn(line);
        }
    }

    /** Every stuck entry with each stuck chunk's registered tickets (type and level). */
    private static List<String> stallDetail(Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets, List<StuckEntry> stuck) {
        List<String> lines = new ArrayList<>();
        for (StuckEntry entry : stuck) {
            lines.add("  stuck " + entry.render());
            SortedArraySet<Ticket<?>> chunkTickets = tickets.get(entry.chunk());
            if (chunkTickets == null) {
                continue;
            }
            for (Ticket<?> ticket : chunkTickets) {
                lines.add("    ticket " + ticket.getType() + " level " + ticket.getTicketLevel());
            }
        }
        return lines;
    }

    private static Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets(ServerLevel level) {
        return ((DistanceManagerAccessor) level.getChunkSource().chunkMap.getDistanceManager()).dimblend$getTickets();
    }

    private static String poolStats() {
        ExecutorService executor = Util.backgroundExecutor();
        if (executor instanceof ForkJoinPool pool) {
            return pool.getQueuedSubmissionCount() + "q/" + pool.getActiveThreadCount() + "a/" + pool.getParallelism() + "p";
        }
        return "n/a";
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        this.pending.clear();
        this.optedOut.clear();
        this.seen.clear();
        this.lastScanTick = -1;
        this.sampleCounter = 0;
        this.ticksSinceLog = LOG_THROTTLE_TICKS;
        if (this.bossbar != null) {
            this.bossbar.removeAllPlayers();
            this.bossbar = null;
        }
    }
}