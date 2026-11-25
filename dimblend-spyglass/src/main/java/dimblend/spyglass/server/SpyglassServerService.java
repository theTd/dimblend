package dimblend.spyglass.server;

import dimblend.api.RegionLanes;
import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassBusyPayload;
import dimblend.spyglass.net.SpyglassSnapshotPayload;
import dimblend.spyglass.structure.SpyglassStructureTags;
import dimblend.spyglass.structure.StructureOutline;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.StructureStateBand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the server side of the spyglass feature.
 *
 * <p>What stays on the server tick thread: player position/dimension, whitelist resolution, the
 * generator's structure states for the scan window ({@link StructureCandidates#bandsFor}, which also
 * guarantees their lazy placement state is generated) and the snapshot packet send. Candidate
 * enumeration, the read-only region scans and the snapshot limiter (pure math) run on a
 * single-threaded executor; the only per-tick work left is polling (every 10 ticks) and telling each
 * scoping player whether a scan is still in flight.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID)
public final class SpyglassServerService {
    private static final int EXECUTOR_QUEUE_CAPACITY = 64;
    /**
     * How many chained batches one poll may start before the scan is left to the normal refresh
     * policy.
     *
     * <p>A batch that skipped chunks chains immediately (see {@code maybeContinue}), which is what
     * drains a ring that is still generating. The count is per poll, not per session: a world that
     * cannot generate the outer ring at all would otherwise re-scan the same 3200-block window
     * forever, on one thread, while the pregenerator is also working. Twenty-four batches gives a
     * full rotating-region scan enough runway to drain before lowering the {@code busy} indicator,
     * while still bounding pathological worlds.
     */
    private static final int MAX_CHAINED_BATCHES = 24;

    private static Session current;

    private SpyglassServerService() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SpyglassRefreshPolicy.POLL_INTERVAL_TICKS == 0) {
            if (current == null || current.server != server) {
                if (current != null) {
                    current.close();
                }
                current = new Session(server);
            }
            current.tick();
        }
        // The busy flag is cheap to reconcile every tick, and the client shows it while the scan
        // executor is still working, independently of the 10-tick poll.
        Session session = current;
        if (session != null && session.server == server) {
            session.syncBusy();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (current != null && event.getEntity() instanceof ServerPlayer player
                && player.server == current.server) {
            PlayerState state = current.states.remove(player.getUUID());
            if (state != null) {
                state.stopScoping();
            }
        }
    }

    /**
     * Forces every scoping player's next poll to rebuild the snapshot. Called by the source panel
     * and by a config reload; it never interrupts a scan that is already running — that result is
     * dropped by the sequence check when the new request finishes.
     */
    public static void invalidateAll() {
        Session session = current;
        if (session != null) {
            session.invalidateAll();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (current != null && current.server == event.getServer()) {
            current.close();
            current = null;
        }
    }

    private static final class Session {
        private final MinecraftServer server;
        private final Map<UUID, PlayerState> states = new HashMap<>();
        private final Map<String, Long> warningTimes = new HashMap<>();
        private final ThreadPoolExecutor executor;
        /** Seed-only replay for chunks with no saved start; carries its own bounded cache. */
        private final StructurePredictor predictor = new StructurePredictor();
        private volatile boolean closed;

        private Session(MinecraftServer server) {
            this.server = server;
            this.executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(runnable, "dimblend-spyglass-scan");
                        thread.setDaemon(true);
                        return thread;
                    });
        }

        private void tick() {
            long now = System.nanoTime();
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
                UUID playerId = player.getUUID();
                online.add(playerId);
                PlayerState state = this.states.get(playerId);
                if (!player.isScoping()) {
                    if (state != null) {
                        state.stopScoping();
                    }
                    continue;
                }

                if (state == null) {
                    state = new PlayerState(playerId);
                    this.states.put(playerId, state);
                }
                state.scoping = true;
                ResourceKey<Level> dimension = player.level().dimension();
                Vec3 position = player.position();
                int region = RegionLanes.regionOfBlockX(player.serverLevel(), Mth.floor(position.x));
                if (SpyglassRefreshPolicy.isRefreshDue(
                        state.requestPosition,
                        state.requestDimension,
                        state.requestRegion,
                        state.requestNanos,
                        state.failedNanos,
                        dimension,
                        region,
                        position,
                        now)) {
                    try {
                        long sequence = ++state.newestSequence;
                        StructureScanRequest request =
                                createRequest(player, sequence, dimension, position);
                        state.requestPosition = position;
                        state.requestDimension = dimension;
                        state.requestRegion = region;
                        state.requestNanos = now;
                        // A fresh poll starts a new chain budget; only continuations consume it.
                        synchronized (state.lock) {
                            state.chainedBatches = 0;
                        }
                        submit(state, request);
                    } catch (RuntimeException exception) {
                        warnLimited(
                                "request",
                                "Failed to prepare spyglass structure scan for {}",
                                new Object[] {playerId},
                                exception);
                    }
                }
                ensureDrain(state);
            }

            Iterator<Map.Entry<UUID, PlayerState>> iterator = this.states.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, PlayerState> entry = iterator.next();
                if (!online.contains(entry.getKey())) {
                    entry.getValue().stopScoping();
                    iterator.remove();
                }
            }
        }

        /**
         * Sends the client-visible "a scan is in flight" flag when it differs from the last one sent
         * for that player. The flag now covers the <em>whole</em> lifecycle — worker running,
         * {@code complete} queued on the server thread, and a chained continuation batch — so the
         * client never sees "not scanning" between two batches of the same requested scan.
         */
        private void syncBusy() {
            if (this.closed) {
                return;
            }
            for (PlayerState state : this.states.values()) {
                boolean busy;
                synchronized (state.lock) {
                    busy = state.scoping && state.busy;
                }
                if (busy == state.busySent) {
                    continue;
                }
                state.busySent = busy;
                ServerPlayer player = this.server.getPlayerList().getPlayer(state.playerId);
                if (player != null) {
                    SpyglassBusyPayload.sendTo(player, busy);
                }
            }
        }

        /** Server tick thread: cheap preparation only. */
        private StructureScanRequest createRequest(
                ServerPlayer player,
                long sequence,
                ResourceKey<Level> dimension,
                Vec3 position) {
            ServerLevel level = player.serverLevel();
            Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            Set<String> disabled = SpyglassServerSettings.disabledSources();
            List<StructureTarget> targets = new ArrayList<>();
            for (Holder<Structure> holder : registry.getTagOrEmpty(SpyglassStructureTags.VISIBLE)) {
                ResourceLocation id = registry.getKey(holder.value());
                if (id != null && SpyglassSourceFilter.keep(id, disabled::contains)) {
                    targets.add(new StructureTarget(id, holder));
                }
            }
            ChunkPos chunk = player.chunkPosition();
            List<StructureStateBand> bands = StructureCandidates.bandsFor(
                    level, chunk.x, chunk.z, StructureCandidates.SCAN_RADIUS_CHUNKS);
            Path regionDirectory = DimensionType.getStorageFolder(
                            dimension, this.server.getWorldPath(LevelResource.ROOT))
                    .resolve("region");
            RegionReadContext region = new RegionReadContext(
                    dimension,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                    regionDirectory,
                    level.getMinBuildHeight(),
                    level.getHeight());
            PredictionContext prediction = predictionContext(level);
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass scan prepared player={} dimension={} whitelisted={} bands={} radius={} prediction={}",
                    player.getUUID(),
                    dimension.location(),
                    targets.size(),
                    bands.size(),
                    StructureCandidates.SCAN_RADIUS_CHUNKS,
                    prediction != null);
            return new StructureScanRequest(
                    player.getUUID(),
                    sequence,
                    position.x,
                    position.z,
                    RegionLanes.bandSize(level),
                    region,
                    targets,
                    bands,
                    prediction);
        }

        /**
         * Server thread: resolves everything a seed-only generation replay needs. Returns {@code null}
         * when prediction cannot be honest for this level — no structure generation, or a generator
         * whose structure state is not available — so the scan simply shows nothing for ungenerated
         * chunks instead of inventing them.
         */
        @Nullable
        private PredictionContext predictionContext(ServerLevel level) {
            if (!level.getServer().getWorldData().worldGenOptions().generateStructures()) {
                return null;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            // A plain generator's structure state lives off the generator's own random state, which
            // the level keeps; a banded generator resolves it per band and does not need it here.
            ChunkGeneratorStructureState state = generator instanceof RotatingChunkGenerator
                    ? null
                    : level.getChunkSource().getGeneratorState();
            try {
                return new PredictionContext(
                        level.dimension(),
                        level.registryAccess(),
                        generator,
                        state,
                        level.structureManager(),
                        this.server.getStructureManager(),
                        LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight()),
                        true);
            } catch (RuntimeException exception) {
                // A generator whose runtime is not ready (dimblend before its seed is set) must not
                // fail the whole scan; prediction is optional.
                DimBlendSpyglass.LOGGER.debug(
                        "Spyglass structure prediction unavailable in {}: {}",
                        level.dimension().location(),
                        exception.toString());
                return null;
            }
        }

        private void submit(PlayerState state, StructureScanRequest request) {
            boolean start;
            synchronized (state.lock) {
                state.latest = request;
                start = !state.running;
                if (start) {
                    state.running = true;
                    state.busy = true;
                }
            }
            if (start) {
                executeDrain(state);
            }
        }

        private void ensureDrain(PlayerState state) {
            boolean start = false;
            synchronized (state.lock) {
                if (!state.running && state.latest != null && state.scoping && !this.closed) {
                    state.running = true;
                    state.busy = true;
                    start = true;
                }
            }
            if (start) {
                executeDrain(state);
            }
        }

        private void executeDrain(PlayerState state) {
            if (this.closed) {
                synchronized (state.lock) {
                    state.running = false;
                    state.busy = false;
                    state.latest = null;
                }
                return;
            }
            try {
                this.executor.execute(() -> runOne(state));
            } catch (RejectedExecutionException exception) {
                synchronized (state.lock) {
                    state.running = false;
                    // The request stays as `latest`, so the next poll retries it and re-raises the
                    // flag; do not leave the client spinning on a scan that is not queued.
                    state.busy = false;
                }
                if (!this.closed) {
                    warnLimited(
                            "queue",
                            "Spyglass structure scan queue is full; pending scans will be retried",
                            new Object[0],
                            null);
                }
            }
        }

        /** Scan executor: candidate enumeration, read-only scans and snapshot limiting. */
        private void runOne(PlayerState state) {
            boolean posted = false;
            try {
                StructureScanRequest request;
                synchronized (state.lock) {
                    request = state.latest;
                    state.latest = null;
                }
                if (request == null || !state.scoping || this.closed) {
                    return;
                }
                try {
                    SpyglassScanOutcome outcome = enumerateAndScan(request);
                    if (!this.closed) {
                        try {
                            this.server.execute(() -> complete(state, request, outcome));
                            posted = true;
                        } catch (RejectedExecutionException ignored) {
                        }
                    }
                } catch (RuntimeException exception) {
                    // The request position/time were recorded when this scan was submitted; without
                    // a failure stamp the next attempt would wait for the full refresh interval.
                    state.failedNanos = System.nanoTime();
                    warnLimited(
                            "scan",
                            "Failed to scan spyglass structures for {}",
                            new Object[] {request.playerId()},
                            exception);
                }
            } finally {
                boolean restart = false;
                synchronized (state.lock) {
                    state.running = false;
                    if (!this.closed && state.scoping && state.latest != null) {
                        state.running = true;
                        restart = true;
                    } else if (!posted) {
                        // Nothing is on its way to the server thread, so this lifecycle ends here:
                        // no request, a failure, a rejected handoff, or the session closing.
                        state.busy = false;
                    }
                }
                if (restart) {
                    executeDrain(state);
                }
            }
        }

        private SpyglassScanOutcome enumerateAndScan(StructureScanRequest request) {
            long started = System.nanoTime();
            List<StructureCandidate> candidates = StructureCandidates.find(
                    request.targets(), request.bands());
            long enumerationNanos = System.nanoTime() - started;
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass enumeration player={} dimension={} candidates={} took={}ms",
                    request.playerId(),
                    request.dimension().location(),
                    candidates.size(),
                    enumerationNanos / 1_000_000L);
            RegionReadErrors errors = new RegionReadErrors();
            List<StructureCandidate> absent = new ArrayList<>();
            // scanInto returns an immutable list; predicted outlines are appended, so take a copy.
            List<StructureOutline> outlines = new ArrayList<>(
                    ReadOnlyRegionScanner.scanInto(request.region(), candidates, absent, errors));
            long predictStarted = System.nanoTime();
            StructurePredictor.Prediction prediction = request.prediction() == null
                    ? StructurePredictor.Prediction.empty()
                    : this.predictor.predict(
                            request.prediction(), absent, request.playerX(), request.playerZ());
            long predictNanos = System.nanoTime() - predictStarted;
            if (!prediction.outlines().isEmpty()) {
                // Predicted outlines join the real ones here so the limiter and the distance ring treat
                // one kind of object; the flag travels with each outline.
                outlines.addAll(prediction.outlines());
            }
            int dropped = keepSameRegion(request, outlines);
            if (dropped > 0) {
                DimBlendSpyglass.LOGGER.debug(
                        "Spyglass dropped {} out-of-region outlines for player={} region-band={}",
                        dropped,
                        request.playerId(),
                        request.bandSize());
            }
            long limitStarted = System.nanoTime();
            SpyglassSnapshotLimiter.LimitResult limited = SpyglassSnapshotLimiter.limit(
                    request.playerX(), request.playerZ(), outlines);
            long limitNanos = System.nanoTime() - limitStarted;
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass scan timings player={} limit={}ms predict={}ms(predicted={} generated={} "
                            + "cached={} skipped={} failed={})",
                    request.playerId(),
                    limitNanos / 1_000_000L,
                    predictNanos / 1_000_000L,
                    prediction.outlines().size(),
                    prediction.generatedChunks(),
                    prediction.cacheHits(),
                    prediction.skippedChunks(),
                    prediction.failedChunks());
            return new SpyglassScanOutcome(limited, errors.snapshot(), outlines.size(), prediction);
        }

        /**
         * Scan executor: keeps only the outlines whose own block X belongs to the player's region.
         *
         * <p>{@link StructureCandidates#clampToRegion} already keeps the <em>window</em> inside the
         * region, but a structure whose start chunk is inside can extend its pieces over the border
         * (and the predictor replays placements for the whole clamped window), so the union centre is
         * the honest test — the same block X {@link RegionLanes#sameRegion(Level, ResourceLocation,
         * int, int)} would compare on the server thread.
         *
         * @return how many outlines were dropped
         */
        private static int keepSameRegion(StructureScanRequest request, List<StructureOutline> outlines) {
            if (request.bandSize() <= 0) {
                return 0;
            }
            int playerBlockX = Mth.floor(request.playerX());
            int before = outlines.size();
            outlines.removeIf(outline -> !RegionLanes.sameRegion(
                    request.bandSize(), Mth.floor(outline.bounds().centerX()), playerBlockX));
            return before - outlines.size();
        }

        private void complete(
                PlayerState state, StructureScanRequest request, SpyglassScanOutcome outcome) {
            if (this.closed || current != this) {
                releaseBusy(state);
                return;
            }
            PlayerState latestState = this.states.get(state.playerId);
            ServerPlayer player = this.server.getPlayerList().getPlayer(state.playerId);
            if (latestState != state
                    || state.newestSequence != request.sequence()
                    || !state.scoping
                    || player == null
                    || !player.isScoping()
                    || !player.level().dimension().location().equals(request.dimension().location())) {
                releaseBusy(state);
                return;
            }
            try {
                SpyglassSnapshotLimiter.LimitResult limited = outcome.limited();
                if (limited.truncatedStructures() > 0) {
                    DimBlendSpyglass.LOGGER.debug(
                            "Spyglass truncated {} structures to {} outline pieces each",
                            limited.truncatedStructures(),
                            SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE);
                }
                if (limited.omittedStructures() > 0) {
                    DimBlendSpyglass.LOGGER.debug(
                            "Spyglass omitted {} complete structures to stay within the snapshot caps",
                            limited.omittedStructures());
                }
                DimBlendSpyglass.LOGGER.debug(
                        "Spyglass scan complete player={} rawOutlines={} visibleOutlines={} pieces={} "
                                + "readFailures={}",
                        request.playerId(),
                        outcome.rawOutlines(),
                        limited.selected().size(),
                        limited.totalPieces(),
                        outcome.readErrors().total());
                reportReadErrors(request, outcome.readErrors());
                SpyglassSnapshotPayload.sendTo(player, request.sequence(), limited.structures());
            } catch (RuntimeException exception) {
                warnLimited(
                        "send",
                        "Failed to send spyglass structure snapshot to {}",
                        new Object[] {request.playerId()},
                        exception);
                releaseBusy(state);
                return;
            }
            maybeContinue(state, request, outcome);
        }

        /**
         * Ends the busy flag unless a worker still owns this player's slot or a request is already
         * queued behind it (that one will report the flag itself).
         */
        private void releaseBusy(PlayerState state) {
            synchronized (state.lock) {
                if (state.running || state.latest != null) {
                    return;
                }
                state.busy = false;
            }
        }

        /**
         * Chained scanning: when a batch reported chunks it had to skip — the predictor's
         * worker-local handoff, a rejected inner task, or a chunk that was still being generated —
         * the scan is not over, only its current batch is. The normal poll would not try again for
         * thirty seconds (or thirty-two blocks), and in a rotating world the player keeps entering
         * fresh chunks the whole time, so the ring would stay permanently half-empty.
         *
         * <p>The continuation is enqueued immediately, at the player's <em>current</em> position,
         * carrying the next sequence number; the busy flag is never lowered across the handoff, so
         * the client keeps showing the scan as in flight. Skipped chunks therefore drain batch by
         * batch until a batch comes back with none (or the player stops scoping, the scan fails, or
         * a newer request already claimed the slot).
         */
        private void maybeContinue(PlayerState state, StructureScanRequest request, SpyglassScanOutcome outcome) {
            ServerPlayer player = this.server.getPlayerList().getPlayer(state.playerId);
            SpyglassBatchLifecycle.AfterBatch decision;
            synchronized (state.lock) {
                if (state.chainedBatches >= MAX_CHAINED_BATCHES) {
                    // The chain budget for this poll is spent: a ring that keeps skipping is left to
                    // the next poll rather than rescanning the same window on one thread forever.
                    decision = SpyglassBatchLifecycle.AfterBatch.DROP_BUSY;
                } else {
                    decision = SpyglassBatchLifecycle.afterBatch(
                            outcome.prediction().skippedChunks(),
                            state.scoping,
                            player != null && player.isScoping(),
                            state.newestSequence != request.sequence(),
                            state.running || state.latest != null);
                }
            }
            if (decision == SpyglassBatchLifecycle.AfterBatch.KEEP_BUSY_FOR_QUEUED_REQUEST) {
                // The worker/queued request that owns the slot reports the flag when it is done.
                return;
            }
            if (decision != SpyglassBatchLifecycle.AfterBatch.CONTINUE_BATCH) {
                releaseBusy(state);
                return;
            }
            long sequence;
            synchronized (state.lock) {
                // A newer request (the poll, a source change, a region crossing) already owns the
                // slot: its result supersedes this batch, so do not chain on top of it.
                if (state.newestSequence != request.sequence() || state.latest != null) {
                    decision = SpyglassBatchLifecycle.AfterBatch.DROP_BUSY;
                    sequence = 0L;
                } else {
                    sequence = ++state.newestSequence;
                    state.chainedBatches++;
                }
            }
            if (decision != SpyglassBatchLifecycle.AfterBatch.CONTINUE_BATCH || player == null) {
                releaseBusy(state);
                return;
            }
            Vec3 position = player.position();
            ResourceKey<Level> dimension = player.level().dimension();
            try {
                StructureScanRequest next = createRequest(player, sequence, dimension, position);
                state.requestPosition = position;
                state.requestDimension = dimension;
                state.requestRegion = RegionLanes.regionOfBlockX(player.serverLevel(), Mth.floor(position.x));
                state.requestNanos = System.nanoTime();
                submit(state, next);
            } catch (RuntimeException exception) {
                state.failedNanos = System.nanoTime();
                warnLimited(
                        "continue",
                        "Failed to prepare spyglass continuation scan for {}",
                        new Object[] {state.playerId},
                        exception);
                releaseBusy(state);
            }
        }

        /**
         * IO/parse/identity failures skip only the failing chunk, so they are reported as one
         * throttled warning per dimension instead of one line per chunk.
         */
        private void reportReadErrors(StructureScanRequest request, RegionScanResult.Counts errors) {
            if (!errors.hasAny()) {
                return;
            }
            warnLimited(
                    "read-errors:" + request.dimension().location(),
                    "Spyglass region reads in {} failed or skipped: region={} chunkRead={} parse={} identity={} unsupportedDataVersion={}",
                    new Object[] {
                            request.dimension().location(),
                            errors.regionFailures(),
                            errors.chunkReadFailures(),
                            errors.parseFailures(),
                            errors.identityFailures(),
                            errors.unsupportedDataVersionSkips()
                    },
                    errors.firstFailure());
        }

        private synchronized void warnLimited(
                String kind, String message, Object[] arguments, @Nullable Throwable failure) {
            long now = System.nanoTime();
            Long previous = this.warningTimes.get(kind);
            if (previous != null && now - previous < SpyglassRefreshPolicy.WARNING_INTERVAL_NANOS) {
                return;
            }
            this.warningTimes.put(kind, now);
            if (failure == null) {
                DimBlendSpyglass.LOGGER.warn(message, arguments);
                return;
            }
            Object[] withFailure = new Object[arguments.length + 1];
            System.arraycopy(arguments, 0, withFailure, 0, arguments.length);
            withFailure[arguments.length] = failure;
            DimBlendSpyglass.LOGGER.warn(message, withFailure);
        }

        private void invalidateAll() {
            for (PlayerState state : this.states.values()) {
                state.invalidate();
            }
        }

        private void close() {
            this.closed = true;
            for (PlayerState state : this.states.values()) {
                state.stopScoping();
            }
            this.states.clear();
            this.predictor.clear();
            this.executor.shutdownNow();
        }
    }

    private static final class PlayerState {
        private final UUID playerId;
        private final Object lock = new Object();
        private volatile boolean scoping;
        private StructureScanRequest latest;
        private boolean running;
        /** A scan lifecycle is in flight: worker, queued complete, or chained continuation. */
        private boolean busy;
        /** Chained continuations started since the last poll, capped by {@code MAX_CHAINED_BATCHES}. */
        private int chainedBatches;
        private long newestSequence;
        private Vec3 requestPosition;
        private ResourceKey<Level> requestDimension;
        /** {@code RegionLanes.regionOfBlockX} of the last request, or null when unknown. */
        private Integer requestRegion;
        private long requestNanos;
        /** Written by the scan executor, read by the tick thread: {@code 0} means no failure. */
        private volatile long failedNanos;
        /** Server tick thread only: the busy flag last sent to the client. */
        private boolean busySent;

        private PlayerState(UUID playerId) {
            this.playerId = playerId;
        }

        /** Makes the next poll rebuild the snapshot; a scan that is already running is not touched. */
        private void invalidate() {
            this.requestPosition = null;
            this.requestDimension = null;
            this.requestRegion = null;
            this.requestNanos = 0L;
            this.failedNanos = 0L;
        }

        /** Forces the next scoping tick to build a fresh snapshot. */
        private void stopScoping() {
            this.scoping = false;
            this.busy = false;
            synchronized (this.lock) {
                this.latest = null;
                this.chainedBatches = 0;
            }
            this.requestPosition = null;
            this.requestDimension = null;
            this.requestRegion = null;
            this.failedNanos = 0L;
        }
    }
}
