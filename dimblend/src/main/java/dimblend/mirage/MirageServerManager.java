package dimblend.mirage;

import com.mojang.logging.LogUtils;
import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.BandSeam;
import dimblend.worldgen.RotatingChunkGenerator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Server side of M1: turns {@link MirageRequestPayload}s into streamed {@link MirageChunkPayload}s.
 *
 * <p><b>Progressive mini-batch streaming.</b> One daemon thread ({@code dimblend-mirage-worker})
 * generates {@value #MINI_BATCH_SIZE} chunks at a time ({@code MirageChunkGenerator.generate} costs
 * ~0.25-0.4 s for that many, against 20-30 s for a whole 218-chunk cap) and hands each finished
 * batch to the per-player send queue immediately, while the server thread sends at most
 * {@value #MAX_SENDS_PER_TICK} payloads per tick — an entire mini-batch per tick. The player's
 * view fills in from the seam outward instead of popping in after the last chunk of a giant
 * batch.</p>
 *
 * <p><b>Nothing is thrown away.</b> A new request never cancels an in-flight mini-batch: each
 * batch is committed to the cache the moment it is generated (even if the player has since
 * crossed the seam or moved along Z) and only then offered to the queue, which itself ignores
 * batches of a seam it no longer follows. Chunks already cached — or already being generated —
 * are filtered by {@link MirageSchedulerQueue}, so a 218-chunk cap costs 218 generations no
 * matter how many requests arrive while it streams.</p>
 *
 * <p><b>Priority.</b> The player's own side of the seam ({@code primary}) always beats the
 * opposite-side prefetch between mini-batches, so looking back across a seam never waits behind
 * a 20 second underground job.</p>
 *
 * <p>Cache: at most {@value #MAX_CACHED_SEAMS} seam sides, each a map of already encoded chunk
 * payloads (up to {@value #MAX_CHUNKS_PER_SEAM}). Walking back and forth across the same seam or
 * several players approaching it reuses the encoded chunks instead of regenerating.</p>
 */
public final class MirageServerManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MirageServerManager INSTANCE = new MirageServerManager();

    /** Chunks per generation pass: small enough to land in the queue within a fifth of a second. */
    static final int MINI_BATCH_SIZE = 12;
    /** A whole mini-batch leaves in one tick (50 ms) once it is generated. */
    static final int MAX_SENDS_PER_TICK = 12;
    static final int MAX_DEPTH_X = MirageCapGeometry.MAX_DEPTH_X;
    static final int MAX_HALF_WIDTH_Z = MirageCapGeometry.MAX_HALF_WIDTH_Z;
    static final double MAX_SEAM_DISTANCE = 400.0;
    /** Bouncing across four seams still hits cached chunks instead of regenerating them. */
    private static final int MAX_CACHED_SEAMS = 8;
    /** Weight of one chunk of depth past the seam in the streaming order, in squared blocks. */
    static final double SEAM_DEPTH_WEIGHT = 128.0;
    /** Seam-band chunks this close (in chunks) to the camera Z go into the very first batch. */
    static final int SEAM_FRONT_DZ_CHUNKS = 3;

    private static final int MAX_CHUNKS_PER_SEAM = 768;

    private volatile Thread worker;

    private final Map<PlayerSideKey, PlayerQueue> queues = new ConcurrentHashMap<>();

    /**
     * Everything below is guarded by {@link #schedulerLock} except the cache, which has its own
     * monitor. Lock order is always {@code schedulerLock -> cache}, never the other way round.
     */
    private final Object schedulerLock = new Object();
    private final MirageSchedulerQueue<PlayerSideKey> schedule =
            new MirageSchedulerQueue<>(MINI_BATCH_SIZE);
    private final Map<PlayerSideKey, PendingJob> pendingJobs = new HashMap<>();
    /** Keys popped for generation but not yet cached: a second request must not duplicate them. */
    private final Set<MirageChunkKey> inFlight = new HashSet<>();
    /** Bumped by {@link #clearAll()}; an in-flight batch of an older epoch is discarded. */
    private long epoch;

    /** Access-ordered LRU; every access and mutation is synchronized on this map. */
    private final LinkedHashMap<Long, Map<MirageChunkKey, MirageChunkPayload>> cache =
            new LinkedHashMap<>(8, 0.75f, true);

    private MirageServerManager() {
    }

    public static MirageServerManager get() {
        return INSTANCE;
    }

    /** Dedicated daemon thread, created on the first request (a client JVM never starts it). */
    private void ensureWorkerStarted() {
        synchronized (this.schedulerLock) {
            if (this.worker != null) {
                return;
            }
            Thread thread = new Thread(this::workerLoop, "dimblend-mirage-worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            this.worker = thread;
            thread.start();
        }
    }

    /** Server thread. Validates the request, answers the lane pair, and queues generation. */
    public void onRequest(ServerPlayer player, MirageRequestPayload request) {
        if (!(player.level() instanceof ServerLevel level)
                || level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return;
        }
        int bandSize = rotating.bandSize();
        int slabX = request.slabX();
        if (!BandSeam.ownsSeamColumn(layout, bandSize, slabX)) {
            return;
        }
        if (request.side() != MirageChunkKey.WEST_BAND_INTO_EAST
                && request.side() != MirageChunkKey.EAST_BAND_INTO_WEST) {
            return;
        }
        if (Math.abs(player.getX() - slabX) > MAX_SEAM_DISTANCE) {
            return;
        }
        if (Math.abs(request.centerChunkZ() - (player.getBlockZ() >> 4)) > 32) {
            // A client may not order generation half a world away; the rectangle is a view aid.
            return;
        }
        int seamRegion = Math.floorDiv(slabX, bandSize);
        String westLane = BandLayout.laneName(layout.delegate(seamRegion - 1));
        String eastLane = BandLayout.laneName(layout.delegate(seamRegion));
        this.send(player, new BandSeamInfoPayload(slabX, westLane, eastLane));

        int depthX = clamp(request.depthX(), 1, MAX_DEPTH_X);
        int halfWidthZ = clamp(request.halfWidthZ(), 0, MAX_HALF_WIDTH_Z);
        List<MirageChunkKey> targets = targetKeys(slabX, request.side(),
                request.centerChunkZ(), depthX, halfWidthZ);
        if (targets.isEmpty()) {
            return;
        }
        long seamId = seamId(slabX, request.side());
        Map<MirageChunkKey, MirageChunkPayload> hits = this.cached(seamId, targets);
        List<MirageChunkKey> missing = new ArrayList<>(targets.size() - hits.size());
        for (MirageChunkKey key : targets) {
            if (!hits.containsKey(key)) {
                missing.add(key);
            }
        }

        UUID playerId = player.getUUID();
        PlayerSideKey channel = new PlayerSideKey(playerId, request.side());
        PlayerQueue queue = this.queues.computeIfAbsent(channel, ignored -> new PlayerQueue());
        // Same seam: keep whatever is already queued (the player moving 1 chunk in Z, or the cap
        // growing, must not discard payloads that are already on their way).
        queue.switchSeamIfNeeded(seamId);

        double eyeX = player.getX();
        double eyeZ = player.getZ();
        if (!hits.isEmpty()) {
            // Cached chunks are already encoded: hand them to the tick sender now, so the very
            // next tick is covered instead of waiting for the worker to finish a mini-batch.
            queue.enqueueBatch(seamId, orderNearToFar(hits.values(), eyeX, player.getY(), eyeZ));
        }
        boolean primary = (eyeX < slabX && request.side() == MirageChunkKey.WEST_BAND_INTO_EAST)
                || (eyeX >= slabX && request.side() == MirageChunkKey.EAST_BAND_INTO_WEST);
        if (missing.isEmpty()) {
            // The cap is complete: forget this channel's pending order but keep the queue.
            synchronized (this.schedulerLock) {
                this.schedule.remove(channel);
                this.pendingJobs.remove(channel);
                this.schedulerLock.notifyAll();
            }
            return;
        }
        List<MirageChunkKey> ordered = orderKeysForStreaming(
                missing, slabX, request.side(), eyeX, eyeZ);
        this.ensureWorkerStarted();
        synchronized (this.schedulerLock) {
            this.pendingJobs.put(channel, new PendingJob(seamId, level, rotating));
            this.schedule.submit(channel, seamId, primary, ordered);
            this.schedulerLock.notifyAll();
        }
    }

    /** Worker thread: takes one mini-batch at a time until it has nothing left to generate. */
    private void workerLoop() {
        try {
            while (true) {
                Work work = this.nextWork();
                if (work == null) {
                    return; // interrupted: the server is going away
                }
                List<MirageChunkPayload> generated;
                try {
                    generated = MirageChunkGenerator.generate(work.level(), work.rotating(), work.keys());
                } catch (RuntimeException failure) {
                    LOGGER.warn("mirage generation failed for {}: {}", work.channel(), failure.toString());
                    generated = List.of();
                }
                synchronized (this.schedulerLock) {
                    this.inFlight.removeAll(work.keys());
                    if (work.epoch() != this.epoch) {
                        continue; // the server unloaded while this batch was generating
                    }
                    if (generated.isEmpty()) {
                        continue;
                    }
                    // Commit before every other consideration: even a batch generated for a request
                    // the player has already replaced is valid work and must not be thrown away.
                    this.store(work.seamId(), generated);
                }
                PlayerQueue queue = this.queues.get(work.channel());
                if (queue != null) {
                    queue.enqueueBatch(work.seamId(), generated);
                }
            }
        } finally {
            // Let a later request start a fresh thread instead of parking on a dead one.
            synchronized (this.schedulerLock) {
                if (this.worker == Thread.currentThread()) {
                    this.worker = null;
                }
            }
        }
    }

    /** Pops the next mini-batch, parking on {@link #schedulerLock} while there is none. */
    private Work nextWork() {
        synchronized (this.schedulerLock) {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                MirageSchedulerQueue.Batch<PlayerSideKey> batch =
                        this.schedule.takeNext(this::alreadyDoneLocked);
                if (batch == null) {
                    // Orders without any remaining key were dropped by the scheduler; prune their
                    // metadata so a later request starts from a clean slate.
                    this.pendingJobs.keySet().removeIf(channel -> !this.schedule.contains(channel));
                    try {
                        this.schedulerLock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                PendingJob job = this.pendingJobs.get(batch.channel());
                if (job == null || job.seamId() != batch.seamId()) {
                    // Bookkeeping out of sync (clearAll or a newer seam): drop and retry.
                    this.schedule.remove(batch.channel());
                    this.pendingJobs.remove(batch.channel());
                    continue;
                }
                this.inFlight.addAll(batch.keys());
                return new Work(batch.channel(), batch.seamId(), job.level(), job.rotating(),
                        batch.keys(), this.epoch);
            }
        }
    }

    /** Cache hit or already in flight: generating it again would be pure waste. */
    private boolean alreadyDoneLocked(MirageChunkKey key) {
        if (this.inFlight.contains(key)) {
            return true;
        }
        synchronized (this.cache) {
            Map<MirageChunkKey, MirageChunkPayload> group =
                    this.cache.get(seamId(key.slabX(), key.side()));
            return group != null && group.containsKey(key);
        }
    }

    /** Up to {@value #MAX_SENDS_PER_TICK} payloads per tick, round-robin over players/sides. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.queues.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int budget = MAX_SENDS_PER_TICK;
        for (Map.Entry<PlayerSideKey, PlayerQueue> entry : this.queues.entrySet()) {
            if (budget <= 0) {
                return;
            }
            PlayerQueue queue = entry.getValue();
            MirageChunkPayload payload;
            while (budget > 0 && (payload = queue.poll()) != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey().playerId());
                if (player == null) {
                    queue.clear();
                    break;
                }
                this.send(player, payload);
                budget--;
            }
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        this.clearAll();
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level
                && level.dimension() == DimBlendRegistries.ROTATING_LEVEL) {
            this.clearAll();
        }
    }

    private void clearAll() {
        synchronized (this.schedulerLock) {
            // A batch generated under the old epoch must not re-populate cleared state.
            this.epoch++;
            this.schedule.clear();
            this.pendingJobs.clear();
            this.inFlight.clear();
            this.schedulerLock.notifyAll();
        }
        for (PlayerQueue queue : this.queues.values()) {
            queue.clear();
        }
        this.queues.clear();
        synchronized (this.cache) {
            this.cache.clear();
        }
    }

    private void send(ServerPlayer player, MirageChunkPayload payload) {
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    private void send(ServerPlayer player, BandSeamInfoPayload payload) {
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    private Map<MirageChunkKey, MirageChunkPayload> cached(
            long seamId, List<MirageChunkKey> targets) {
        Map<MirageChunkKey, MirageChunkPayload> out = new LinkedHashMap<>();
        synchronized (this.cache) {
            Map<MirageChunkKey, MirageChunkPayload> group = this.cache.get(seamId);
            if (group == null) {
                return out;
            }
            for (MirageChunkKey key : targets) {
                MirageChunkPayload payload = group.get(key);
                if (payload != null) {
                    out.put(key, payload);
                }
            }
        }
        return out;
    }

    private void store(long seamId, List<MirageChunkPayload> payloads) {
        synchronized (this.cache) {
            Map<MirageChunkKey, MirageChunkPayload> group =
                    this.cache.computeIfAbsent(seamId, ignored -> new LinkedHashMap<>());
            for (MirageChunkPayload payload : payloads) {
                group.put(payload.key(), payload);
            }
            while (group.size() > MAX_CHUNKS_PER_SEAM) {
                Iterator<MirageChunkKey> eldest = group.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            while (this.cache.size() > MAX_CACHED_SEAMS) {
                Iterator<Long> eldest = this.cache.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        }
    }

    private static List<MirageChunkKey> targetKeys(
            int slabX, byte side, int centerChunkZ, int depthX, int halfWidthZ) {
        int fromX = MirageCapGeometry.firstChunkX(slabX, side, depthX);
        int toX = MirageCapGeometry.lastChunkX(slabX, side, depthX);
        List<MirageChunkKey> keys = new ArrayList<>(depthX * (2 * halfWidthZ + 1));
        for (int chunkX = fromX; chunkX <= toX; chunkX++) {
            for (int chunkZ = centerChunkZ - halfWidthZ; chunkZ <= centerChunkZ + halfWidthZ; chunkZ++) {
                // Only the elliptical cap: the truncated corners are invisible at every angle, and
                // generating them costs a full column of noise/surface/decoration for nothing.
                if (!MirageCapGeometry.inCap(
                        slabX, side, centerChunkZ, depthX, halfWidthZ, chunkX, chunkZ)) {
                    continue;
                }
                keys.add(new MirageChunkKey(slabX, side, chunkX, chunkZ));
            }
        }
        return keys;
    }

    /**
     * Streaming order of the chunks still to generate: the wall directly in front of the player's
     * eyes first, then nearest-to-farthest with each chunk of depth past the seam penalised.
     *
     * <p>The live capture is the reason for the explicit front tier: flying toward a seam at
     * 20 blocks/s, a plain distance sort put the chunk under the camera first while the wall the
     * player actually looks at sat several mini-batches back. {@code dx == 0} within
     * {@value #SEAM_FRONT_DZ_CHUNKS} chunks of the eye Z therefore always lands in the first
     * {@value #MINI_BATCH_SIZE}-chunk batch (the cap's seam band holds at least five such chunks),
     * and {@link #SEAM_DEPTH_WEIGHT} keeps the deeper bands behind the front one.</p>
     */
    public static List<MirageChunkKey> orderKeysForStreaming(
            Collection<MirageChunkKey> keys, int slabX, byte side, double eyeX, double eyeZ) {
        List<MirageChunkKey> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator
                .comparingInt((MirageChunkKey key) -> frontTier(key, slabX, side, eyeZ))
                .thenComparingDouble(key -> streamScore(key, slabX, side, eyeX, eyeZ))
                .thenComparingInt(MirageChunkKey::chunkX)
                .thenComparingInt(MirageChunkKey::chunkZ));
        return ordered;
    }

    /** 0 for the seam band under the camera, 1 for everything else. */
    private static int frontTier(MirageChunkKey key, int slabX, byte side, double eyeZ) {
        if (depthOffset(key, slabX, side) != 0) {
            return 1;
        }
        double dzBlocks = Math.abs(((key.chunkZ() << 4) + 8.0) - eyeZ);
        return dzBlocks <= SEAM_FRONT_DZ_CHUNKS * 16.0 ? 0 : 1;
    }

    /** Squared distance from the chunk centre to the eye, plus a penalty per chunk of depth. */
    private static double streamScore(
            MirageChunkKey key, int slabX, byte side, double eyeX, double eyeZ) {
        double centerX = (key.chunkX() << 4) + 8.0;
        double centerZ = (key.chunkZ() << 4) + 8.0;
        double dx = centerX - eyeX;
        double dz = centerZ - eyeZ;
        double depth = Math.max(0, depthOffset(key, slabX, side));
        return dx * dx + dz * dz + depth * depth * SEAM_DEPTH_WEIGHT;
    }

    /** Chunks of depth past the seam: 0 is the seam band, growing away from the player. */
    private static int depthOffset(MirageChunkKey key, int slabX, byte side) {
        int seamChunkX = slabX >> 4;
        return side == MirageChunkKey.WEST_BAND_INTO_EAST
                ? key.chunkX() - seamChunkX : seamChunkX - 1 - key.chunkX();
    }

    private static List<MirageChunkPayload> orderNearToFar(
            Iterable<MirageChunkPayload> payloads, double eyeX, double eyeY, double eyeZ) {
        List<MirageChunkPayload> ordered = new ArrayList<>();
        for (MirageChunkPayload payload : payloads) {
            ordered.add(payload);
        }
        ordered.sort((a, b) -> Double.compare(distanceSq(a, eyeX, eyeY, eyeZ),
                distanceSq(b, eyeX, eyeY, eyeZ)));
        return ordered;
    }

    private static double distanceSq(MirageChunkPayload payload, double x, double y, double z) {
        double centerX = (payload.key().chunkX() << 4) + 8.0;
        double centerZ = (payload.key().chunkZ() << 4) + 8.0;
        double dx = centerX - x;
        double dz = centerZ - z;
        return dx * dx + dz * dz;
    }

    private static long seamId(int slabX, byte side) {
        return ((long) slabX << 1) | (side == MirageChunkKey.WEST_BAND_INTO_EAST ? 1L : 0L);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * One player's streamed payloads for one seam side.
     *
     * <p>Only a <em>different</em> seam drops what is still queued: moving one chunk along Z or
     * growing the cap keeps the payloads that are already waiting for a send slot, which is what
     * turns a 218-chunk cap into one continuous stream instead of a restart per tick.</p>
     */
    static final class PlayerQueue {
        private static final long NO_SEAM = Long.MIN_VALUE;

        private final ConcurrentLinkedQueue<MirageChunkPayload> payloads = new ConcurrentLinkedQueue<>();
        private final Set<MirageChunkKey> queuedKeys = new HashSet<>();
        private long currentSeamId = NO_SEAM;

        synchronized void switchSeamIfNeeded(long seamId) {
            if (this.currentSeamId == seamId) {
                return;
            }
            this.currentSeamId = seamId;
            this.payloads.clear();
            this.queuedKeys.clear();
        }

        /** False when the batch belongs to a seam this queue no longer follows. */
        synchronized boolean enqueueBatch(long seamId, Collection<MirageChunkPayload> batch) {
            if (this.currentSeamId != seamId) {
                return false;
            }
            boolean added = false;
            for (MirageChunkPayload payload : batch) {
                if (this.queuedKeys.add(payload.key())) {
                    this.payloads.add(payload);
                    added = true;
                }
            }
            return added;
        }

        /** Server thread: the next payload to send, or null. */
        synchronized MirageChunkPayload poll() {
            MirageChunkPayload payload = this.payloads.poll();
            if (payload != null) {
                this.queuedKeys.remove(payload.key());
            }
            return payload;
        }

        synchronized void clear() {
            this.payloads.clear();
            this.queuedKeys.clear();
        }

        synchronized long currentSeamId() {
            return this.currentSeamId;
        }

        synchronized int queuedCount() {
            return this.queuedKeys.size();
        }

        synchronized boolean isQueued(MirageChunkKey key) {
            return this.queuedKeys.contains(key);
        }
    }

    private record PlayerSideKey(UUID playerId, byte side) {
    }

    /** Everything the worker needs to generate a mini-batch; the level is the live server level. */
    private record Work(
            PlayerSideKey channel,
            long seamId,
            ServerLevel level,
            RotatingChunkGenerator rotating,
            List<MirageChunkKey> keys,
            long epoch) {
    }

    /** Per-channel scheduling metadata, paired with the scheduler's key order. */
    private record PendingJob(long seamId, ServerLevel level, RotatingChunkGenerator rotating) {
    }
}
