package dimblend.spyglass.server;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassSnapshotPayload;
import dimblend.spyglass.structure.SpyglassDistance;
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
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
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
 * guarantees their lazy placement state is generated), the snapshot/shape packet sends and the
 * per-tick shape streaming. Candidate enumeration, the read-only region scans, the snapshot limiter
 * (pure math) and the shape extraction with its encoding run on a single-threaded executor.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID)
public final class SpyglassServerService {
    private static final int EXECUTOR_QUEUE_CAPACITY = 64;

    private static Session current;

    private SpyglassServerService() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SpyglassRefreshPolicy.POLL_INTERVAL_TICKS != 0) {
            // Shape streaming runs every tick, independent of the (10-tick) scan poll.
            Session streaming = current;
            if (streaming != null && streaming.server == server) {
                streaming.streamShapes();
            }
            return;
        }
        if (current == null || current.server != server) {
            if (current != null) {
                current.close();
            }
            current = new Session(server);
        }
        current.tick();
        current.streamShapes();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (current != null && event.getEntity() instanceof ServerPlayer player
                && player.server == current.server) {
            PlayerState state = current.states.remove(player.getUUID());
            if (state != null) {
                state.stopScoping();
            }
            current.reveals.remove(player.getUUID());
            current.shapeStreamer.remove(player.getUUID());
        }
    }

    /**
     * Server thread (payload handler): stores the client's reveal distance for the next snapshot.
     * Values are clamped to the display range, so a malformed or malicious packet cannot make the
     * server scan for structures outside the ring.
     */
    public static void acceptReveal(ServerPlayer player, int blocks) {
        Session session = current;
        if (session == null || session.server != player.server) {
            return;
        }
        session.reveals.put(
                player.getUUID(), Mth.clamp(blocks, 0, (int) SpyglassDistance.MAX_BLOCKS));
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
        /** Reveal distance each player's client reported; absent means the client has not sent one. */
        private final Map<UUID, Integer> reveals = new HashMap<>();
        private final Map<String, Long> warningTimes = new HashMap<>();
        private final ThreadPoolExecutor executor;
        private final SpyglassServerShapeCache shapeCache = new SpyglassServerShapeCache();
        private final SpyglassShapeStreamer shapeStreamer = new SpyglassShapeStreamer();
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
                        this.shapeStreamer.dropPending(playerId);
                    }
                    continue;
                }

                if (state == null) {
                    state = new PlayerState(playerId);
                    this.states.put(playerId, state);
                }
                if (state.player != player) {
                    // A respawn (or any level replacement) builds a new ServerPlayer while the UUID
                    // stays. The client drops its level-derived caches with the old level, so the
                    // server has to forget which shapes the client has.
                    if (state.player != null) {
                        this.shapeStreamer.resetTracking(playerId);
                    }
                    state.player = player;
                }
                state.scoping = true;
                ResourceKey<Level> dimension = player.level().dimension();
                Vec3 position = player.position();
                double revealBlocks = revealBlocks(player);
                if (SpyglassRefreshPolicy.isRefreshDue(
                        state.requestPosition,
                        state.requestDimension,
                        state.requestReveal,
                        state.requestNanos,
                        state.failedNanos,
                        dimension,
                        position,
                        revealBlocks,
                        now)) {
                    try {
                        long sequence = ++state.newestSequence;
                        StructureScanRequest request =
                                createRequest(player, sequence, dimension, position, revealBlocks);
                        state.requestPosition = position;
                        state.requestDimension = dimension;
                        state.requestReveal = revealBlocks;
                        state.requestNanos = now;
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
                PlayerState state = entry.getValue();
                if (!online.contains(entry.getKey())) {
                    state.stopScoping();
                    this.shapeStreamer.remove(entry.getKey());
                    iterator.remove();
                }
            }
        }

        /**
         * The client's reported reveal distance, or vanilla's fog start for the player's effective
         * view distance until the client has sent one. Receiving the value is independent of scoping,
         * so it is kept in the session rather than in the (scoping-only) {@link PlayerState}.
         */
        private double revealBlocks(ServerPlayer player) {
            Integer received = this.reveals.get(player.getUUID());
            if (received != null) {
                return received;
            }
            return SpyglassRevealDefaults.defaultRevealBlocks(
                    player.requestedViewDistance(), this.server.getPlayerList().getViewDistance());
        }

        /** Server tick thread: cheap preparation only. */
        private StructureScanRequest createRequest(
                ServerPlayer player,
                long sequence,
                ResourceKey<Level> dimension,
                Vec3 position,
                double revealBlocks) {
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
            Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            PredictionContext prediction = predictionContext(level);
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass scan prepared player={} dimension={} whitelisted={} bands={} radius={} reveal={} prediction={}",
                    player.getUUID(),
                    dimension.location(),
                    targets.size(),
                    bands.size(),
                    StructureCandidates.SCAN_RADIUS_CHUNKS,
                    revealBlocks,
                    prediction != null);
            return new StructureScanRequest(
                    player.getUUID(),
                    sequence,
                    position.x,
                    position.z,
                    revealBlocks,
                    region,
                    biomes,
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
                    state.latest = null;
                }
                return;
            }
            try {
                this.executor.execute(() -> runOne(state));
            } catch (RejectedExecutionException exception) {
                synchronized (state.lock) {
                    state.running = false;
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

        /** Scan executor: candidate enumeration, read-only scans and shape extraction. */
        private void runOne(PlayerState state) {
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
                // Predicted outlines join the real ones here so the limiter, the distance ring and the
                // renderer treat one kind of object; the flag travels with each outline.
                outlines.addAll(prediction.outlines());
            }
            long limitStarted = System.nanoTime();
            SpyglassSnapshotLimiter.LimitResult limited = SpyglassSnapshotLimiter.limit(
                    request.playerX(), request.playerZ(), request.revealBlocks(), outlines);
            long limitNanos = System.nanoTime() - limitStarted;
            long shapeStarted = System.nanoTime();
            // Shape extraction only ever runs for outlines that have blocks on disk. A predicted
            // outline has no chunk to read, so it is filtered out before the extraction rather than
            // counted as a read failure.
            StructureShapeExtractor.Extraction extraction = StructureShapeExtractor.extract(
                    request.region(),
                    request.biomes(),
                    limited.selected().stream()
                            .filter(structure -> !structure.outline().predicted())
                            .toList(),
                    this.shapeCache,
                    errors);
            long shapeNanos = System.nanoTime() - shapeStarted;
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass scan timings player={} limit={}ms predict={}ms(predicted={} generated={} "
                            + "cached={} skipped={} failed={}) shapes={}ms",
                    request.playerId(),
                    limitNanos / 1_000_000L,
                    predictNanos / 1_000_000L,
                    prediction.outlines().size(),
                    prediction.generatedChunks(),
                    prediction.cacheHits(),
                    prediction.skippedChunks(),
                    prediction.failedChunks(),
                    shapeNanos / 1_000_000L);
            return new SpyglassScanOutcome(
                    limited, extraction, errors.snapshot(), outlines.size(), prediction);
        }

        private void complete(
                PlayerState state, StructureScanRequest request, SpyglassScanOutcome outcome) {
            if (this.closed || current != this) {
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
                StructureShapeExtractor.ShapeStats shapeStats = outcome.extraction().stats();
                DimBlendSpyglass.LOGGER.debug(
                        "Spyglass scan complete player={} rawOutlines={} visibleOutlines={} pieces={} "
                                + "shapes={} noShape(notGenerated={} readFailed={} tooManyBlocks={} "
                                + "empty={} unencodable={} visibilityVolumeTooLarge={}) cacheHits={} "
                                + "readFailures={}",
                        request.playerId(),
                        outcome.rawOutlines(),
                        limited.selected().size(),
                        limited.totalPieces(),
                        shapeStats.extracted(),
                        shapeStats.notGenerated(),
                        shapeStats.readFailed(),
                        shapeStats.tooManyBlocks(),
                        shapeStats.empty(),
                        shapeStats.unencodable(),
                        shapeStats.visibilityVolumeTooLarge(),
                        shapeStats.cacheHits(),
                        outcome.readErrors().total());
                reportReadErrors(request, outcome.readErrors());
                SpyglassSnapshotPayload.sendTo(player, request.sequence(), limited.structures());
                this.shapeStreamer.onSnapshot(
                        request.playerId(), request.dimension(), outcome.extraction().shapes());
            } catch (RuntimeException exception) {
                warnLimited(
                        "send",
                        "Failed to send spyglass structure snapshot to {}",
                        new Object[] {request.playerId()},
                        exception);
            }
        }

        /** Sends queued shapes within the per-player budget; safe to call every tick. */
        private void streamShapes() {
            if (this.closed) {
                return;
            }
            this.shapeStreamer.stream(this.server);
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
            this.reveals.clear();
            this.shapeStreamer.clear();
            this.shapeCache.clear();
            this.predictor.clear();
            this.executor.shutdownNow();
        }
    }

    private static final class PlayerState {
        private final UUID playerId;
        private final Object lock = new Object();
        private volatile boolean scoping;
        private ServerPlayer player;
        private StructureScanRequest latest;
        private boolean running;
        private long newestSequence;
        private Vec3 requestPosition;
        private ResourceKey<Level> requestDimension;
        /** Reveal distance the current snapshot was built for; compared by the refresh policy. */
        private double requestReveal;
        private long requestNanos;
        /** Written by the scan executor, read by the tick thread: {@code 0} means no failure. */
        private volatile long failedNanos;

        private PlayerState(UUID playerId) {
            this.playerId = playerId;
        }

        /** Makes the next poll rebuild the snapshot; a scan that is already running is not touched. */
        private void invalidate() {
            this.requestPosition = null;
            this.requestDimension = null;
            this.requestNanos = 0L;
            this.failedNanos = 0L;
        }

        /** Forces the next scoping tick to build a fresh snapshot. */
        private void stopScoping() {
            this.scoping = false;
            synchronized (this.lock) {
                this.latest = null;
            }
            this.requestPosition = null;
            this.requestDimension = null;
            this.failedNanos = 0L;
        }
    }
}
