package dimblend.purge;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.SableCompanion;
import dimblend.DimBlend;
import dimblend.DimBlendRegistries;
import dimblend.mixin.ChunkMapAccessor;
import dimblend.mixin.DistanceManagerAccessor;
import dimblend.mixin.EntityStorageAccessor;
import dimblend.mixin.PersistentEntitySectionManagerAccessor;
import dimblend.mixin.SectionStorageAccessor;
import dimblend.mixin.ServerLevelCachesAccessor;
import dimblend.mixin.SimpleRegionStorageAccessor;
import dimblend.worldgen.PregenConfig;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Deletes rotating-dimension region files far from every known player position while the server
 * runs. Unit of deletion is one {@code r.X.Z.mca} region (32x32 chunks) across the chunk, entity
 * and POI stores; deleted regions regenerate from the seed if anyone comes back.
 *
 * <p>A region is deleted only when, for {@code idleSeconds} of consecutive scans:
 * <ul>
 *   <li>every keep center is more than the effective keep distance away along X. Keep centers are
 *       the persisted last known X of every player who has been in the dimension
 *       ({@link RegionPurgeAnchors}: live position while inside, departure point while logged out
 *       or in another dimension);</li>
 *   <li>no chunk of it has a holder, a pending unload, a ticket (forced chunks, Sable, Create and
 *       every other ticket source included) or entity state in memory;</li>
 *   <li>it is outside Sable's plot grid (sub-level storage, not terrain);</li>
 *   <li>saving is on ({@code /save-off} pauses purge so backups see a consistent world).</li>
 * </ul>
 * With no keep center at all nothing is deleted: "behind" is undefined without a traveller.
 */
public final class RegionPurgeController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FORCE_SCAN_LIMIT = 64;
    /** A region file no larger than its 8 KiB header cannot hold a chunk (data starts at sector 2). */
    private static final long HEADER_ONLY_BYTES = 8192L;
    /** Header-only files are recreated by far reads ({@code /locate}, maps); swept outside the budget. */
    private static final int HEADER_ONLY_PER_SCAN = 64;
    private static final long FAILURE_BACKOFF_TICKS = 20L * 60L * 5L;

    public record Snapshot(
            boolean enabled,
            String state,
            int keepChunks,
            int effectiveKeepChunks,
            int idleSeconds,
            int scanIntervalSeconds,
            int keepCenters,
            int regionsOnDisk,
            int busyRegions,
            int counting,
            int inFlight,
            long regionsDeleted,
            long headerOnlyRemoved,
            long filesDeleted,
            long bytesFreed,
            long deferred,
            long failed,
            @Nullable String lastRegion,
            long lastBytes
    ) {
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.ROOT, "purge  %s (%s)  keep=%dch (effective %dch)  idle=%ds  scan=%ds",
                    this.enabled ? "on" : "off", this.state, this.keepChunks, this.effectiveKeepChunks,
                    this.idleSeconds, this.scanIntervalSeconds));
            lines.add(String.format(Locale.ROOT, "scan   regions=%d  busy=%d  counting=%d  deleting=%d  centers=%d",
                    this.regionsOnDisk, this.busyRegions, this.counting, this.inFlight, this.keepCenters));
            lines.add(String.format(Locale.ROOT, "total  regions=%d  empty=%d  files=%d  freed=%s  deferred=%d  failed=%d",
                    this.regionsDeleted, this.headerOnlyRemoved, this.filesDeleted, humanBytes(this.bytesFreed),
                    this.deferred, this.failed));
            if (this.lastRegion != null) {
                lines.add("last   " + this.lastRegion + "  " + humanBytes(this.lastBytes));
            }
            return lines;
        }
    }

    /** One store's worker plus its label for logs. */
    private record Store(String name, IOWorker worker) {
    }

    private final RegionPurgePlanner planner = new RegionPurgePlanner();
    private final LongOpenHashSet inFlight = new LongOpenHashSet();
    /** Region key to the tick before which a failed delete is not retried. */
    private final Long2LongOpenHashMap retryAfter = new Long2LongOpenHashMap();
    /** Bumped on server stop so completions from a previous server are ignored. */
    private int epoch;
    /** A scan's region-file listing runs off-thread; set until its finishScan lands. */
    private boolean scanInFlight;
    private int ticksUntilScan;
    private String lastState = "not scanned";
    private int lastRegionsOnDisk;
    private int lastBusyRegions;
    private int lastKeepCenters;
    private long regionsDeleted;
    private long headerOnlyRemoved;
    private long filesDeleted;
    private long bytesFreed;
    private long deferred;
    private long failed;
    @Nullable
    private String lastRegion;
    private long lastBytes;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (RegionPurgeConfig.ENABLED.get()) {
            LOGGER.warn("dimblend purge is enabled: rotating-dimension region files more than {} chunks along X from every"
                            + " known player position are deleted while the server runs ({} to disable)",
                    effectiveKeepChunks(event.getServer()), RegionPurgeConfig.FILE_NAME);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!RegionPurgeConfig.ENABLED.get()) {
            this.planner.clear();
            this.lastState = "disabled";
            return;
        }
        if (--this.ticksUntilScan > 0) {
            return;
        }
        this.ticksUntilScan = RegionPurgeConfig.SCAN_INTERVAL_SECONDS.get() * 20;
        this.scan(event.getServer(), RegionPurgeConfig.IDLE_SECONDS.get() * 20L,
                RegionPurgeConfig.MAX_REGIONS_PER_SCAN.get(), OptionalInt.empty());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        this.epoch++;
        this.planner.clear();
        this.inFlight.clear();
        this.retryAfter.clear();
        this.scanInFlight = false;
        this.ticksUntilScan = 0;
        this.lastState = "not scanned";
        this.lastRegionsOnDisk = 0;
        this.lastBusyRegions = 0;
        this.lastKeepCenters = 0;
    }

    /** Pins the exact spot a player logs out at; scans only sample every few seconds. */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            recordIfInRotating(player);
        }
    }

    /** Pins the departure point before the player leaves through a portal or a teleport. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            recordIfInRotating(player);
        }
    }

    /**
     * Operator scan that skips the idle timer; keep window and loaded-state checks still apply.
     * The scan is asynchronous (region-file listing runs off-thread); the submitted count is
     * logged when the scan lands (also visible via {@code /dimblend purge}).
     *
     * @param extraKeepChunkX additional keep center for this scan only, so a console can purge
     *                        around the spot players will come back to
     * @return 0 when a scan was scheduled, or -1 when purge is disabled in config
     */
    public int scanNow(MinecraftServer server, OptionalInt extraKeepChunkX) {
        if (!RegionPurgeConfig.ENABLED.get()) {
            return -1;
        }
        return this.scan(server, 0L, FORCE_SCAN_LIMIT, extraKeepChunkX);
    }

    public Snapshot snapshot(MinecraftServer server) {
        return new Snapshot(
                RegionPurgeConfig.ENABLED.get(),
                this.lastState,
                RegionPurgeConfig.KEEP_CHUNKS.get(),
                effectiveKeepChunks(server),
                RegionPurgeConfig.IDLE_SECONDS.get(),
                RegionPurgeConfig.SCAN_INTERVAL_SECONDS.get(),
                this.lastKeepCenters,
                this.lastRegionsOnDisk,
                this.lastBusyRegions,
                this.planner.tracked(),
                this.inFlight.size(),
                this.regionsDeleted,
                this.headerOnlyRemoved,
                this.filesDeleted,
                this.bytesFreed,
                this.deferred,
                this.failed,
                this.lastRegion,
                this.lastBytes
        );
    }

    /**
     * Schedules a scan. Only the region-file listing (directory walk + file sizes — the part that
     * stalls the server thread for seconds on a big rotating dimension) runs on the IO pool;
     * everything touching live world state (anchors, busy check, prune, delete submission) stays
     * on the server thread in {@link #finishScan}, same tick as the busy re-check.
     *
     * @return always 0: the submitted count is only known when the scan lands (logged there)
     */
    private int scan(MinecraftServer server, long idleTicks, int limit, OptionalInt extraKeepChunkX) {
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            this.lastState = "no rotating level";
            return 0;
        }
        if (level.noSave()) {
            this.planner.clear();
            this.lastState = "paused by save-off";
            return 0;
        }
        List<Store> stores = stores(level);
        if (stores.isEmpty()) {
            this.lastState = "unsupported chunk storage";
            return 0;
        }
        if (this.scanInFlight) {
            this.lastState = "scan in flight";
            return 0;
        }
        this.scanInFlight = true;
        int submittedEpoch = this.epoch;
        Util.ioPool().execute(() -> {
            Long2LongOpenHashMap largestFile = new Long2LongOpenHashMap();
            Store failedStore = null;
            IOException failure = null;
            for (Store store : stores) {
                try {
                    RegionFileDeleter.listRegions(RegionFileDeleter.folderOf(store.worker()), largestFile);
                } catch (IOException e) {
                    failedStore = store;
                    failure = e;
                    break;
                }
            }
            Store errorStore = failedStore;
            IOException error = failure;
            server.execute(() -> this.finishScan(server, submittedEpoch, level, stores, largestFile,
                    errorStore, error, idleTicks, limit, extraKeepChunkX));
        });
        return 0;
    }

    /** Server-thread scan completion: re-reads every live input, then plans and submits deletions. */
    private void finishScan(MinecraftServer server, int submittedEpoch, ServerLevel level, List<Store> stores,
            Long2LongOpenHashMap largestFile, @Nullable Store failedStore, @Nullable IOException failure,
            long idleTicks, int limit, OptionalInt extraKeepChunkX) {
        this.scanInFlight = false;
        if (submittedEpoch != this.epoch) {
            return;
        }
        if (failure != null) {
            LOGGER.warn("dimblend purge: cannot list {} regions", failedStore.name(), failure);
            this.lastState = "listing failed";
            return;
        }
        if (!RegionPurgeConfig.ENABLED.get()) {
            this.lastState = "disabled";
            return;
        }
        if (level.noSave()) {
            this.planner.clear();
            this.lastState = "paused by save-off";
            return;
        }
        int[] centers = keepCenters(level, extraKeepChunkX);
        this.lastKeepCenters = centers.length;
        this.lastRegionsOnDisk = largestFile.size();
        // Same tick as the delete submissions below: nothing can load the region in between.
        LongSet busy = busyRegions(level);
        this.lastBusyRegions = busy.size();
        if (centers.length == 0) {
            this.planner.clear();
            this.lastState = "no keep center";
            return;
        }
        long now = server.getTickCount();
        this.retryAfter.long2LongEntrySet().removeIf(
                entry -> entry.getLongValue() <= now || !largestFile.containsKey(entry.getLongKey()));
        int keep = effectiveKeepChunks(server);
        LongPredicate eligible = key -> !busy.contains(key)
                && !this.inFlight.contains(key)
                && !this.retryAfter.containsKey(key)
                && RegionPurgePlanner.nearestCenterDistance(RegionPurgePlanner.keyX(key), centers) > keep
                && !inSablePlotGrid(level, key);

        LongOpenHashSet withChunks = new LongOpenHashSet();
        LongOpenHashSet headerOnly = new LongOpenHashSet();
        for (Long2LongMap.Entry entry : largestFile.long2LongEntrySet()) {
            (entry.getLongValue() > HEADER_ONLY_BYTES ? withChunks : headerOnly).add(entry.getLongKey());
        }
        long[] ready = this.planner.plan(
                withChunks,
                eligible,
                key -> RegionPurgePlanner.nearestCenterDistance(RegionPurgePlanner.keyX(key), centers),
                now,
                idleTicks,
                limit
        );
        for (long key : ready) {
            this.purge(server, level, stores, key, false);
        }
        int swept = 0;
        for (long key : headerOnly) {
            if (swept >= HEADER_ONLY_PER_SCAN) {
                break;
            }
            if (eligible.test(key)) {
                this.purge(server, level, stores, key, true);
                swept++;
            }
        }
        this.lastState = "active";
        if (ready.length + swept > 0) {
            LOGGER.info("dimblend purge: scan submitted {} region(s)", ready.length + swept);
        }
    }

    private void purge(MinecraftServer server, ServerLevel level, List<Store> stores, long key, boolean headerOnly) {
        int regionX = RegionPurgePlanner.keyX(key);
        int regionZ = RegionPurgePlanner.keyZ(key);
        // Same tick as the busy check, before any IO task: nothing can load the region in between.
        RegionMemoryPruner.Pruned pruned = RegionMemoryPruner.prune(level, regionX, regionZ);
        DimBlend.pregen().forgetRegion(regionX, regionZ);
        this.inFlight.add(key);
        int submittedEpoch = this.epoch;
        List<CompletableFuture<RegionFileDeleter.Result>> futures = new ArrayList<>(stores.size());
        for (Store store : stores) {
            futures.add(RegionFileDeleter.delete(store.worker(), regionX, regionZ));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                server.execute(() -> this.onDeleted(server, submittedEpoch, key, headerOnly, stores, futures, pruned)));
    }

    private void onDeleted(
            MinecraftServer server,
            int submittedEpoch,
            long key,
            boolean headerOnly,
            List<Store> stores,
            List<CompletableFuture<RegionFileDeleter.Result>> futures,
            RegionMemoryPruner.Pruned pruned
    ) {
        if (submittedEpoch != this.epoch) {
            return;
        }
        this.inFlight.remove(key);
        String region = "r." + RegionPurgePlanner.keyX(key) + "." + RegionPurgePlanner.keyZ(key);
        long bytes = 0L;
        int files = 0;
        boolean anyDeferred = false;
        RuntimeException failure = null;
        String failedStore = null;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < futures.size(); i++) {
            Store store = stores.get(i);
            RegionFileDeleter.Result result;
            try {
                result = futures.get(i).join();
            } catch (RuntimeException e) {
                failure = e;
                failedStore = store.name();
                continue;
            }
            if (result.outcome() == RegionFileDeleter.Outcome.DEFERRED) {
                anyDeferred = true;
                continue;
            }
            bytes += result.bytes();
            files += result.files();
            detail.append(' ').append(store.name()).append('=').append(humanBytes(result.bytes()));
        }
        this.bytesFreed += bytes;
        this.filesDeleted += files;
        if (failure != null) {
            this.failed++;
            this.planner.forget(key);
            this.retryAfter.put(key, server.getTickCount() + FAILURE_BACKOFF_TICKS);
            LOGGER.warn("dimblend purge: deleting {} {} failed, retrying in {} s",
                    failedStore, region, FAILURE_BACKOFF_TICKS / 20, failure);
        } else if (anyDeferred) {
            this.deferred++;
            LOGGER.debug("dimblend purge: {} still has queued writes, retrying next scan", region);
        } else if (headerOnly) {
            this.headerOnlyRemoved++;
            LOGGER.debug("dimblend purge: removed header-only {}", region);
        } else {
            this.regionsDeleted++;
            this.planner.forget(key);
            this.lastRegion = region;
            this.lastBytes = bytes;
            LOGGER.info("dimblend purge: deleted {} ({} files,{}; memo poi={} structure={})",
                    region, files, detail, pruned.poiSections(), pruned.structureChunks());
        }
    }

    private static void recordIfInRotating(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() == DimBlendRegistries.ROTATING_LEVEL) {
            RegionPurgeAnchors.get(level).record(player.getUUID(), player.chunkPosition().x);
        }
    }

    private static List<Store> stores(ServerLevel level) {
        List<Store> stores = new ArrayList<>(3);
        if (level.getChunkSource().chunkMap.chunkScanner() instanceof IOWorker chunks) {
            stores.add(new Store("region", chunks));
        } else {
            return List.of();
        }
        EntityPersistentStorage<?> entityStorage =
                ((PersistentEntitySectionManagerAccessor) ((ServerLevelCachesAccessor) level).dimblend$getEntityManager())
                        .dimblend$getPermanentStorage();
        if (entityStorage instanceof EntityStorage entities) {
            SimpleRegionStorageAccessor entityRegions =
                    (SimpleRegionStorageAccessor) ((EntityStorageAccessor) entities).dimblend$getSimpleRegionStorage();
            stores.add(new Store("entities", entityRegions.dimblend$getWorker()));
        }
        SectionStorageAccessor poi = (SectionStorageAccessor) level.getPoiManager();
        stores.add(new Store("poi", ((SimpleRegionStorageAccessor) poi.dimblend$getSimpleRegionStorage()).dimblend$getWorker()));
        return stores;
    }

    /** Regions with any chunk or entity state in memory, or any ticket. Server thread only. */
    private static LongSet busyRegions(ServerLevel level) {
        LongOpenHashSet busy = new LongOpenHashSet();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkMapAccessor chunks = (ChunkMapAccessor) chunkMap;
        for (long chunk : chunks.dimblend$getUpdatingChunkMap().keySet()) {
            busy.add(RegionPurgePlanner.regionKeyOfChunk(chunk));
        }
        for (long chunk : chunks.dimblend$getPendingUnloads().keySet()) {
            busy.add(RegionPurgePlanner.regionKeyOfChunk(chunk));
        }
        for (long chunk : ((DistanceManagerAccessor) chunkMap.getDistanceManager()).dimblend$getTickets().keySet()) {
            busy.add(RegionPurgePlanner.regionKeyOfChunk(chunk));
        }
        PersistentEntitySectionManagerAccessor entities =
                (PersistentEntitySectionManagerAccessor) ((ServerLevelCachesAccessor) level).dimblend$getEntityManager();
        for (long chunk : entities.dimblend$getChunkLoadStatuses().keySet()) {
            busy.add(RegionPurgePlanner.regionKeyOfChunk(chunk));
        }
        for (long chunk : entities.dimblend$getChunksToUnload()) {
            busy.add(RegionPurgePlanner.regionKeyOfChunk(chunk));
        }
        for (long chunk : entities.dimblend$getSectionStorage().getAllChunksWithExistingSections()) {
            busy.add(RegionPurgePlanner.regionKeyOfChunk(chunk));
        }
        return busy;
    }

    /** Refreshes live anchors of players inside, then returns every known anchor plus the extra one. */
    private static int[] keepCenters(ServerLevel level, OptionalInt extraKeepChunkX) {
        RegionPurgeAnchors anchors = RegionPurgeAnchors.get(level);
        for (ServerPlayer player : level.players()) {
            anchors.record(player.getUUID(), player.chunkPosition().x);
        }
        int[] known = anchors.chunkXs();
        if (extraKeepChunkX.isEmpty()) {
            return known;
        }
        int[] centers = new int[known.length + 1];
        System.arraycopy(known, 0, centers, 0, known.length);
        centers[known.length] = extraKeepChunkX.getAsInt();
        return centers;
    }

    private static int effectiveKeepChunks(MinecraftServer server) {
        return RegionPurgePlanner.effectiveKeepChunks(
                RegionPurgeConfig.KEEP_CHUNKS.get(),
                PregenConfig.X_BEHIND.get(),
                PregenConfig.X_AHEAD.get(),
                server.getPlayerList().getViewDistance()
        );
    }

    /** Sable keeps sub-level blocks in a far-away plot grid; those regions are not terrain. */
    private static boolean inSablePlotGrid(ServerLevel level, long key) {
        int minX = RegionPurgePlanner.keyX(key) << RegionPurgePlanner.CHUNKS_PER_REGION_SHIFT;
        int minZ = RegionPurgePlanner.keyZ(key) << RegionPurgePlanner.CHUNKS_PER_REGION_SHIFT;
        int maxX = minX + RegionPurgePlanner.CHUNKS_PER_REGION - 1;
        int maxZ = minZ + RegionPurgePlanner.CHUNKS_PER_REGION - 1;
        SableCompanion sable = SableCompanion.INSTANCE;
        return sable.isInPlotGrid(level, minX, minZ) || sable.isInPlotGrid(level, maxX, maxZ)
                || sable.isInPlotGrid(level, minX, maxZ) || sable.isInPlotGrid(level, maxX, minZ);
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
