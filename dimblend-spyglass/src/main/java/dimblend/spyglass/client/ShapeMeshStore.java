package dimblend.spyglass.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShape;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Lifetime of the real-shape meshes: requests builds on a dedicated worker, uploads finished ones
 * on the render thread within a per-frame budget, and keeps them in a vertex-bounded LRU
 * ({@link ShapeMeshCache}) keyed by structure, newest revision wins.
 *
 * <ul>
 *   <li><b>Threading.</b> One daemon thread ({@value #WORKER_NAME}) meshes {@link StructureShape}s,
 *       which are immutable; the render thread captures everything else the build needs
 *       ({@link StructureShapeBlockGetter.LevelValues}, the block render dispatcher, the sort
 *       origin) before submitting. At most {@value #MAX_IN_FLIGHT} builds are queued or running, at
 *       most one new build is requested per frame and at most {@value #MAX_UPLOADS_PER_FRAME}
 *       finished meshes (and, after the first, at most {@value #MAX_UPLOAD_VERTICES_PER_FRAME}
 *       vertices) are uploaded per frame.</li>
 *   <li><b>Once per revision.</b> {@link ShapeBuildTracker} keeps a {@code (key, revision)} from being
 *       built twice; failures and oversized meshes are not retried until the next clear.</li>
 *   <li><b>Clear.</b> {@link #clear()} runs wherever {@link SpyglassClientState#clear()} runs
 *       (dimension change, disconnect, world switch), on a resource reload (atlas UVs change) and when
 *       the graphics mode or smooth lighting changes (layers and AO change). It bumps an epoch, so a
 *       build that finishes afterwards is closed instead of uploaded.</li>
 * </ul>
 *
 * <p>Render thread only, except the worker body, which only touches its own arguments and the
 * concurrent result queue.
 */
final class ShapeMeshStore {
    /** GPU budget: ~256 MiB of chunk-format vertices (32 bytes each). */
    static final long MAX_TOTAL_VERTICES = 8_000_000L;
    /** One structure may use at most a quarter of the budget (~64 MiB); larger ones are not shown. */
    static final long MAX_MESH_VERTICES = MAX_TOTAL_VERTICES / 4L;
    /** Entry cap, so empty meshes (zero vertices) cannot pile up. */
    static final int MAX_ENTRIES = 1024;
    static final int MAX_IN_FLIGHT = 2;
    static final int MAX_UPLOADS_PER_FRAME = 2;
    /** Stop uploading for this frame once this many vertices went up (the first upload always goes). */
    static final long MAX_UPLOAD_VERTICES_PER_FRAME = 1_000_000L;
    /** After an over-budget rejection, wait about ten seconds before trying the same revision again. */
    static final long OVER_BUDGET_RETRY_FRAMES = 600L;
    static final String WORKER_NAME = "dimblend-spyglass-mesh";

    /** What the real-shape pass did with a structure's mesh in the current frame. */
    enum MeshUse {
        /** No usable mesh: the structure is not shown. */
        NONE,
        /** The mesh is usable but outside the view this frame: draw nothing. */
        OFF_SCREEN,
        /** The mesh was drawn this frame, in the far or the overlay pass: it gets a label. */
        DRAWN
    }

    private record Completed(
            int epoch,
            StructureKey key,
            int revision,
            @Nullable BuiltShapeMesh mesh,
            @Nullable Throwable failure,
            int blockCount,
            long buildNanos) {
    }

    private static final ShapeMeshCache<UploadedShapeMesh> CACHE =
            new ShapeMeshCache<>(MAX_TOTAL_VERTICES, MAX_MESH_VERTICES, MAX_ENTRIES);
    private static final ShapeBuildTracker TRACKER = new ShapeBuildTracker(MAX_IN_FLIGHT);
    private static final ConcurrentLinkedQueue<Completed> COMPLETED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static @Nullable ExecutorService executor;
    private static long frame;
    private static boolean settingsKnown;
    private static @Nullable GraphicsStatus graphicsMode;
    private static boolean ambientOcclusion;
    private static boolean buildFailureLogged;
    private static boolean blockFailureLogged;
    private static boolean tooLargeLogged;

    private ShapeMeshStore() {
    }

    /**
     * Starts a frame of the real-shape pass: advances the frame stamp, drops all meshes if the
     * graphics settings changed, and uploads finished builds within the per-frame budget. Called on
     * every level frame, scoping or not, so finished builds never wait in the queue for long.
     */
    static long beginFrame(Minecraft minecraft) {
        RenderSystem.assertOnRenderThread();
        frame++;
        checkGraphicsSettings(minecraft);
        drainCompleted();
        return frame;
    }

    /** The current frame stamp; the AFTER_PARTICLES overlay pass of the same frame reads it. */
    static long frame() {
        return frame;
    }

    /** The cached mesh of this structure (possibly an older revision), without touching its recency. */
    static @Nullable UploadedShapeMesh peek(StructureKey key) {
        return CACHE.peek(key);
    }

    /**
     * Stamps the structure's mesh as drawn this frame. Only drawn meshes count as recently used, so
     * under memory pressure off-screen meshes are evicted before anything on screen is rejected.
     */
    static void markUsed(StructureKey key) {
        CACHE.use(key, frame);
    }

    /** What the selection pass of this frame did for the structure; for the label hand-off. */
    static MeshUse meshUse(StructureKey key) {
        UploadedShapeMesh mesh = CACHE.peek(key);
        if (mesh == null || !mesh.selectedIn(frame)) {
            return MeshUse.NONE;
        }
        return mesh.drawnIn(frame) ? MeshUse.DRAWN : MeshUse.OFF_SCREEN;
    }

    /** Whether a build of this shape's revision may be requested now. */
    static boolean canRequest(StructureShape shape) {
        return TRACKER.canStart(shape.key(), shape.revision(), frame);
    }

    /**
     * Queues a mesh build of this shape revision. The level values, dispatcher and sort origin are
     * captured here on the render thread; the worker never touches the client level.
     */
    static void request(StructureShape shape, ClientLevel level, Vec3 camera, BlockRenderDispatcher dispatcher) {
        RenderSystem.assertOnRenderThread();
        if (!canRequest(shape)) {
            return;
        }
        StructureShapeBlockGetter.LevelValues values = captureLevelValues(level);
        float sortX = (float) (camera.x - shape.originX());
        float sortY = (float) (camera.y - shape.originY());
        float sortZ = (float) (camera.z - shape.originZ());
        int epoch = EPOCH.get();
        StructureKey key = shape.key();
        TRACKER.started(key, shape.revision());
        try {
            executor().execute(() -> build(epoch, shape, values, dispatcher, sortX, sortY, sortZ));
        } catch (RejectedExecutionException exception) {
            TRACKER.finished(key);
            TRACKER.reject(key, shape.revision(), ShapeBuildTracker.NEVER);
            DimBlendSpyglass.LOGGER.warn("Spyglass mesh worker rejected structure {}", key, exception);
        }
    }

    /**
     * Drops every mesh and forgets every request. Safe to call from any thread: off the render thread
     * it is deferred to it, because deleting GL buffers must happen there.
     */
    static void clear() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(ShapeMeshStore::clear);
            return;
        }
        EPOCH.incrementAndGet();
        TRACKER.clear();
        Completed done;
        while ((done = COMPLETED.poll()) != null) {
            if (done.mesh() != null) {
                done.mesh().close();
            }
        }
        CACHE.clear();
    }

    static long cachedVertices() {
        return CACHE.totalVertices();
    }

    static int cachedMeshes() {
        return CACHE.size();
    }

    private static void build(
            int epoch,
            StructureShape shape,
            StructureShapeBlockGetter.LevelValues values,
            BlockRenderDispatcher dispatcher,
            float sortX,
            float sortY,
            float sortZ) {
        StructureKey key = shape.key();
        if (epoch != EPOCH.get()) {
            // Cleared while queued: skip the work entirely.
            COMPLETED.add(new Completed(epoch, key, shape.revision(), null, null, shape.blockCount(), 0L));
            return;
        }
        long start = System.nanoTime();
        try {
            BuiltShapeMesh mesh = ShapeMeshBuilder.build(shape, values, dispatcher, sortX, sortY, sortZ);
            if (epoch != EPOCH.get()) {
                // Free the native memory here instead of parking it in the queue until the next frame.
                mesh.close();
                mesh = null;
            }
            COMPLETED.add(new Completed(
                    epoch, key, shape.revision(), mesh, null, shape.blockCount(), System.nanoTime() - start));
        } catch (Throwable failure) {
            COMPLETED.add(new Completed(
                    epoch, key, shape.revision(), null, failure, shape.blockCount(), System.nanoTime() - start));
        }
    }

    private static void drainCompleted() {
        int current = EPOCH.get();
        int uploads = 0;
        long uploadedVertices = 0L;
        while (uploads < MAX_UPLOADS_PER_FRAME && uploadedVertices < MAX_UPLOAD_VERTICES_PER_FRAME) {
            Completed done = COMPLETED.poll();
            if (done == null) {
                return;
            }
            BuiltShapeMesh built = done.mesh();
            if (done.epoch() != current) {
                if (built != null) {
                    built.close();
                }
                continue;
            }
            StructureKey key = done.key();
            TRACKER.finished(key);
            if (done.failure() != null || built == null) {
                TRACKER.reject(key, done.revision(), ShapeBuildTracker.NEVER);
                logBuildFailure(done);
                continue;
            }
            logBlockFailures(built);
            if (built.vertexCount() > MAX_MESH_VERTICES) {
                built.close();
                TRACKER.reject(key, done.revision(), ShapeBuildTracker.NEVER);
                logTooLarge(key, built.vertexCount());
                continue;
            }
            UploadedShapeMesh uploaded;
            try {
                uploaded = built.upload();
            } catch (RuntimeException exception) {
                TRACKER.reject(key, done.revision(), ShapeBuildTracker.NEVER);
                DimBlendSpyglass.LOGGER.warn("Spyglass could not upload the mesh of structure {}", key, exception);
                continue;
            }
            uploads++;
            uploadedVertices += uploaded.vertexCount();
            ShapeMeshCache.PutResult result = CACHE.put(key, uploaded, frame);
            switch (result) {
                case TOO_LARGE -> TRACKER.reject(key, done.revision(), ShapeBuildTracker.NEVER);
                case OVER_BUDGET -> TRACKER.reject(key, done.revision(), frame + OVER_BUDGET_RETRY_FRAMES);
                case STORED, STALE -> {
                }
            }
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass meshed structure {} revision {}: {} blocks -> {} vertices in {} ms, cache {}; "
                            + "{} meshes / {} vertices cached",
                    key,
                    done.revision(),
                    done.blockCount(),
                    uploaded.vertexCount(),
                    TimeUnit.NANOSECONDS.toMillis(done.buildNanos()),
                    result,
                    CACHE.size(),
                    CACHE.totalVertices());
        }
    }

    private static void checkGraphicsSettings(Minecraft minecraft) {
        GraphicsStatus mode = minecraft.options.graphicsMode().get();
        boolean smoothLighting = Minecraft.useAmbientOcclusion();
        if (settingsKnown && (mode != graphicsMode || smoothLighting != ambientOcclusion)) {
            // Leaves switch layer with the graphics mode and AO changes every vertex color; vanilla
            // rebuilds all sections for both (LevelRenderer.allChanged), so rebuild the meshes too.
            clear();
        }
        settingsKnown = true;
        graphicsMode = mode;
        ambientOcclusion = smoothLighting;
    }

    private static StructureShapeBlockGetter.LevelValues captureLevelValues(ClientLevel level) {
        float[] shades = new float[Direction.values().length * 2];
        for (Direction direction : Direction.values()) {
            shades[StructureShapeBlockGetter.LevelValues.shadeIndex(direction, false)] = level.getShade(direction, false);
            shades[StructureShapeBlockGetter.LevelValues.shadeIndex(direction, true)] = level.getShade(direction, true);
        }
        Optional<Holder<Biome>> plains = level.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getHolder(Biomes.PLAINS)
                .map(holder -> (Holder<Biome>) holder);
        return new StructureShapeBlockGetter.LevelValues(
                shades, level.effects().constantAmbientLight(), level.getMinBuildHeight(), level.getHeight(), plains);
    }

    private static ExecutorService executor() {
        ExecutorService current = executor;
        if (current == null) {
            current = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, WORKER_NAME);
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.setUncaughtExceptionHandler((failed, exception) ->
                        DimBlendSpyglass.LOGGER.error("Uncaught exception on {}", failed.getName(), exception));
                return thread;
            });
            executor = current;
        }
        return current;
    }

    private static void logBuildFailure(Completed done) {
        if (buildFailureLogged) {
            DimBlendSpyglass.LOGGER.debug("Spyglass could not mesh structure {} revision {}",
                    done.key(), done.revision(), done.failure());
            return;
        }
        buildFailureLogged = true;
        DimBlendSpyglass.LOGGER.warn(
                "Spyglass could not mesh structure {} revision {}; it is not shown (further failures at DEBUG)",
                done.key(), done.revision(), done.failure());
    }

    private static void logBlockFailures(BuiltShapeMesh built) {
        if (built.failedBlocks() == 0) {
            return;
        }
        if (blockFailureLogged) {
            DimBlendSpyglass.LOGGER.debug("Spyglass skipped {} block(s) of structure {} whose model failed",
                    built.failedBlocks(), built.key());
            return;
        }
        blockFailureLogged = true;
        DimBlendSpyglass.LOGGER.warn(
                "Spyglass skipped {} block(s) of structure {} whose model failed to render, first {} "
                        + "(further reports at DEBUG)",
                built.failedBlocks(), built.key(), built.firstFailedState(), built.firstFailure());
    }

    private static void logTooLarge(StructureKey key, long vertices) {
        if (tooLargeLogged) {
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass structure {} meshes to {} vertices; not shown", key, vertices);
            return;
        }
        tooLargeLogged = true;
        DimBlendSpyglass.LOGGER.warn(
                "Spyglass structure {} meshes to {} vertices, above the per-structure limit of {}; "
                        + "it is not shown (further reports at DEBUG)",
                key, vertices, MAX_MESH_VERTICES);
    }
}
