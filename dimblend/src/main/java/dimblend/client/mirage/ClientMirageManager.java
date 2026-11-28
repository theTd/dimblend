package dimblend.client.mirage;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dimblend.DimBlendRegistries;
import dimblend.client.ClientBandLane;
import dimblend.client.ClientBandProgress;
import dimblend.client.bandrift.BandLightTexture;
import dimblend.client.bandrift.BandRiftMode;
import dimblend.client.bandrift.BandRiftShaders;
import dimblend.client.bandrift.VolumetricRiftMath;
import dimblend.client.bandrift.VolumetricRiftState;
import dimblend.mirage.BandSeamInfoClientReceiver;
import dimblend.mirage.BandSeamInfoPayload;
import dimblend.mirage.MirageCapGeometry;
import dimblend.mirage.MirageChunkKey;
import dimblend.mirage.MirageChunkPayload;
import dimblend.mirage.MirageClientReceiver;
import dimblend.mirage.MirageRequestPayload;
import dimblend.worldgen.OakTrackCorridor;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * Client-side M1: seam discovery, mirage requests, the bake queue, and the static mesh draw.
 *
 * <p><b>Seam discovery does not probe blocks.</b> W2 removed the null-block wall, so a new world
 * has no {@code dimblend:warp_gate} at {@code X = 4096}; probing would find nothing. Every tick the
 * manager finds the candidate seams {@code k * bandSize} whose block is actually within
 * {@link MirageCapGeometry#requestRadius(int)} of the camera (a {@code floorDiv} window alone can
 * be two thousand blocks wide) and sends one small {@link MirageRequestPayload} per side. The
 * server validates with {@code BandSeam.ownsSeamColumn} and answers with
 * {@link BandSeamInfoPayload} plus the first mirage chunks; a same-lane boundary never answers.
 * A seam only becomes <em>active</em> once its two lane names are known and differ. Old worlds
 * that still contain a legacy gate column are detected by a cheap block scan as a fallback, with
 * the lane pair taken from the band sync.</p>
 *
 * <p><b>What is requested.</b> On the side the camera is on, the near band's terrain into the far
 * columns ({@code side = +1} west of the seam, {@code -1} east) — that is the seamless continuation
 * the player sees at any distance. The footprint is the elliptical cap from
 * {@link MirageCapGeometry#capShape(double, int)} for the current render distance and seam
 * distance, and it only ever <em>grows</em> while approaching: backing away never re-requests a
 * smaller cap, so walking costs nothing. Within {@link #CAVITY_RADIUS} the opposite side is
 * prefetched too, so crossing the seam and looking back is instant. Requests repeat only when the
 * centre chunk Z moved by one chunk, the cap grew, or {@link #RESEND_TICKS} passed while chunks of
 * the cap are still missing — a complete cap never re-requests, so the server never replays
 * payloads the client would only count as dropped. A stale resend is also swallowed while chunk
 * payloads are still arriving ({@link MirageRequestPlanner#STREAM_ACTIVE_TICKS}): the server
 * streams chunk mini-batches for several seconds, and asking again in the middle of that used to
 * make it throw away everything it had already generated.</p>
 *
 * <p><b>Baking.</b> Payloads become {@link ClientMirageChunk}s immediately (main thread); sections
 * are baked from {@link #tickBakeStep()} under a 6 ms budget, called from the render stage, so a
 * 300-chunk cap never costs a frame spike. Everything GL happens on the render thread.</p>
 */
public final class ClientMirageManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CAVITY_RADIUS = 56;
    /** Opposite-side prefetch around the seam: crossing and looking back needs no bake at all. */
    private static final MirageCapGeometry.CapShape CAVITY_SHAPE = new MirageCapGeometry.CapShape(4, 5);
    private static final int RESEND_TICKS = 100;
    private static final int LEGACY_PROBE_TICKS = 20;
    private static final long BAKE_BUDGET_NANOS = 6_000_000L;

    private static final Map<Integer, Seam> SEAMS = new LinkedHashMap<>();
    private static final Map<Long, Side> SIDES = new HashMap<>();
    private static final ArrayDeque<BakeJob> BAKE = new ArrayDeque<>();

    private static long tick;
    /** Last client tick on which a chunk payload was accepted; 0 = nothing received yet. */
    private static long lastPayloadTick;
    private static int requestsSent;
    private static int payloads;
    private static int bakedSections;
    private static int discarded;

    private ClientMirageManager() {
    }

    /** Wires the two client sinks; called from {@code DimBlendClient.register}. */
    public static void register() {
        MirageClientReceiver.register(ClientMirageManager::onChunkPayload);
        BandSeamInfoClientReceiver.register(ClientMirageManager::onSeamInfo);
    }

    // ---- seam + payload plumbing ---------------------------------------------------------------

    /** One candidate seam and its lane pair, once known. */
    private static final class Seam {
        final int slabX;
        String westLane;
        String eastLane;
        final Request[] requests = {new Request(), new Request()};
        long probedTick;

        Seam(int slabX) {
            this.slabX = slabX;
        }

        boolean active() {
            return this.westLane != null && this.eastLane != null && !this.westLane.equals(this.eastLane);
        }

        Request request(byte side) {
            return this.requests[side == MirageChunkKey.WEST_BAND_INTO_EAST ? 0 : 1];
        }
    }

    private static final class Request {
        int centerChunkZ = Integer.MIN_VALUE;
        int depth = -1;
        int halfWidth = -1;
        long lastTick;
    }

    /** All mirage chunks of one (seam, side). */
    private static final class Side {
        final Map<Long, ClientMirageChunk> chunks = new HashMap<>();
        String laneName = "";
    }

    private record BakeJob(ClientMirageChunk chunk, int sectionIndex) {
    }

    /** Active seam snapshot for the renderer. */
    public record ActiveSeam(int slabX, String westLane, String eastLane, double distance) {
        public String nearLane(double cameraX) {
            return cameraX < this.slabX ? this.westLane : this.eastLane;
        }

        public String farLane(double cameraX) {
            return cameraX < this.slabX ? this.eastLane : this.westLane;
        }
    }

    private static long sideKey(int slabX, byte side) {
        return ((long) slabX << 2) | (side == MirageChunkKey.WEST_BAND_INTO_EAST ? 1L : 2L);
    }

    private static void onSeamInfo(BandSeamInfoPayload payload) {
        Seam seam = SEAMS.computeIfAbsent(payload.slabX(), Seam::new);
        seam.westLane = payload.westLane();
        seam.eastLane = payload.eastLane();
    }

    private static void onChunkPayload(MirageChunkPayload payload) {
        MirageChunkKey key = payload.key();
        long sideKey = sideKey(key.slabX(), key.side());
        Side side = SIDES.computeIfAbsent(sideKey, ignored -> new Side());
        side.laneName = payload.laneName();
        long chunkKey = ChunkPos.asLong(key.chunkX(), key.chunkZ());
        if (side.chunks.containsKey(chunkKey)) {
            discarded++;
            return; // the server cache replays identical chunks; keep the built VBOs
        }
        ClientMirageChunk chunk = ClientMirageChunk.decode(payload);
        if (chunk == null) {
            discarded++;
            return;
        }
        side.chunks.put(chunkKey, chunk);
        lastPayloadTick = tick;
        Seam seam = SEAMS.computeIfAbsent(key.slabX(), Seam::new);
        if (key.side() == MirageChunkKey.WEST_BAND_INTO_EAST) {
            seam.westLane = payload.laneName();
            seam.eastLane = payload.otherLaneName();
        } else {
            seam.eastLane = payload.laneName();
            seam.westLane = payload.otherLaneName();
        }
        for (int sectionIndex : chunk.bakeOrder()) {
            BAKE.addLast(new BakeJob(chunk, sectionIndex));
        }
        payloads++;
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Game-bus listener: discovery, requests, and chunk eviction. */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        int bandSize = ClientBandProgress.bandSize();
        if (!rotating(level) || bandSize <= 0) {
            clearAll();
            return;
        }
        tick++;
        // Discovery and eviction run in every mode (the membrane and the diag view need the seam
        // identity); only RIFT actually asks the server for mirage chunks.
        boolean wantMirage = BandRiftMode.current() == BandRiftMode.RIFT;
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int centerChunkZ = Mth.floor(camera.z) >> 4;
        int rdChunks = rdChunks();
        for (int slabX : MirageRequestPlanner.candidateSlabs(
                camera.x, bandSize, MirageCapGeometry.requestRadius(rdChunks))) {
            Seam seam = SEAMS.computeIfAbsent(slabX, Seam::new);
            double distance = Math.abs(slabX - camera.x);
            byte primary = camera.x < slabX ? MirageChunkKey.WEST_BAND_INTO_EAST : MirageChunkKey.EAST_BAND_INTO_WEST;
            if (wantMirage) {
                // The cap grows as the camera approaches; the grow-only rule below keeps a walk
                // away from the seam completely free of packets.
                request(seam, primary, centerChunkZ,
                        MirageCapGeometry.capShape(distance, rdChunks), true);
                if (distance <= CAVITY_RADIUS) {
                    byte opposite = primary == MirageChunkKey.WEST_BAND_INTO_EAST
                            ? MirageChunkKey.EAST_BAND_INTO_WEST : MirageChunkKey.WEST_BAND_INTO_EAST;
                    request(seam, opposite, centerChunkZ, CAVITY_SHAPE, false);
                }
            }
            if (!seam.active()) {
                probeLegacyGate(seam, level, camera.x);
            }
        }
        dropFar(camera.x, MirageCapGeometry.dropRadius(rdChunks));
    }

    /** Render distance in chunks, clamped to the range the cap geometry is tuned for. */
    public static int rdChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return MirageCapGeometry.DEFAULT_RENDER_DISTANCE_CHUNKS;
        }
        Integer chunks = minecraft.options.renderDistance().get();
        return MirageCapGeometry.clampRenderDistanceChunks(
                chunks == null ? MirageCapGeometry.DEFAULT_RENDER_DISTANCE_CHUNKS : chunks);
    }

    private static void request(
            Seam seam, byte side, int centerChunkZ, MirageCapGeometry.CapShape shape, boolean growOnly) {
        Request state = seam.request(side);
        boolean sent = state.lastTick != 0L;
        boolean moved = sent && Math.abs(centerChunkZ - state.centerChunkZ) >= 1;
        boolean reshaped;
        if (!sent) {
            reshaped = true;
        } else if (growOnly) {
            // Approaching expands the cap (depth and chord both grow monotonically); walking away
            // would shrink it, but the chunks already on the client still cover the smaller shape,
            // so a smaller shape is "not reshaped" and costs zero packets.
            reshaped = shape.depthX() > state.depth || shape.halfWidthZ() > state.halfWidth;
        } else {
            reshaped = state.depth != shape.depthX() || state.halfWidth != shape.halfWidthZ();
        }
        boolean stale = sent && tick - state.lastTick >= RESEND_TICKS;
        if (sent && !moved && !reshaped && !stale) {
            return;
        }
        boolean streamActive = MirageRequestPlanner.streamActive(lastPayloadTick, tick);
        if (MirageRequestPlanner.suppressesStale(sent, moved, reshaped, stale, streamActive)) {
            // Mini-batches are still arriving: a retry now would make the server restart a queue
            // that is already making progress, and the chunks in flight would be lost.
            // Recording the shape and the tick stops the stale timer from re-testing every tick.
            state.depth = shape.depthX();
            state.halfWidth = shape.halfWidthZ();
            state.lastTick = tick;
            return;
        }
        boolean complete = sent && sideComplete(seam.slabX, side, centerChunkZ, shape.depthX(), shape.halfWidthZ());
        if (MirageRequestPlanner.suppressesResend(sent, moved, reshaped, complete)) {
            // The cap is whole: a resend would only make the server replay unchanged payloads,
            // which the client counts as dropped. Only a genuinely missing chunk retries.
            // Recording the shape too stops the grow-only rule from re-testing the same growth
            // every tick while walking in 2-chunk steps.
            state.depth = shape.depthX();
            state.halfWidth = shape.halfWidthZ();
            state.lastTick = tick;
            return;
        }
        state.centerChunkZ = centerChunkZ;
        state.depth = shape.depthX();
        state.halfWidth = shape.halfWidthZ();
        state.lastTick = tick;
        PacketDistributor.sendToServer(new MirageRequestPayload(
                seam.slabX, side, centerChunkZ, shape.depthX(), shape.halfWidthZ()));
        requestsSent++;
    }

    /** True when every chunk of the {@code (slabX, side)} rectangle is present in the cache. */
    static boolean sideComplete(int slabX, byte side, int centerChunkZ, int depth, int halfWidth) {
        Side buffer = SIDES.get(sideKey(slabX, side));
        return buffer != null && MirageRequestPlanner.sideComplete(
                slabX, side, centerChunkZ, depth, halfWidth,
                (chunkX, chunkZ) -> buffer.chunks.containsKey(ChunkPos.asLong(chunkX, chunkZ)));
    }

    /**
     * Old worlds only: a gate column still exists at {@code slabX}. The lane pair comes from the
     * band sync (current band + outward neighbour), because such a world also predates
     * {@link BandSeamInfoPayload}.
     */
    private static void probeLegacyGate(Seam seam, ClientLevel level, double cameraX) {
        if (tick - seam.probedTick < LEGACY_PROBE_TICKS) {
            return;
        }
        seam.probedTick = tick;
        int chunkX = seam.slabX >> 4;
        int chunkZ = OakTrackCorridor.CORRIDOR_Z >> 4;
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return;
        }
        boolean found = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = OakTrackCorridor.VAULT_FLOOR_DY; dy <= OakTrackCorridor.VAULT_APEX_DY && !found; dy++) {
            pos.set(seam.slabX, OakTrackCorridor.TRACK_Y + dy, OakTrackCorridor.CORRIDOR_Z);
            found = level.getBlockState(pos).is(DimBlendRegistries.WARP_GATE.get());
        }
        if (!found) {
            return;
        }
        String currentLane = ClientBandLane.lane();
        String outwardLane = ClientBandLane.nextLane();
        String other = outwardLane == null || outwardLane.isEmpty() ? "unknown" : outwardLane;
        if (cameraX < seam.slabX) {
            seam.westLane = currentLane;
            seam.eastLane = other;
        } else {
            seam.eastLane = currentLane;
            seam.westLane = other;
        }
    }

    private static void dropFar(double cameraX, int dropRadius) {
        SEAMS.entrySet().removeIf(entry -> {
            if (Math.abs(entry.getKey() - cameraX) <= dropRadius) {
                return false;
            }
            releaseSeam(entry.getKey());
            return true;
        });
    }

    private static void releaseSeam(int slabX) {
        for (byte side : new byte[]{MirageChunkKey.WEST_BAND_INTO_EAST, MirageChunkKey.EAST_BAND_INTO_WEST}) {
            Side buffer = SIDES.remove(sideKey(slabX, side));
            if (buffer != null) {
                for (ClientMirageChunk chunk : buffer.chunks.values()) {
                    chunk.release();
                }
                buffer.chunks.clear();
            }
        }
        BAKE.removeIf(job -> job.chunk().key().slabX() == slabX);
    }

    // ---- frame ---------------------------------------------------------------------------------

    /** Drains the bake queue for at most {@link #BAKE_BUDGET_NANOS}. Render thread only. */
    public static void tickBakeStep() {
        if (BAKE.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + BAKE_BUDGET_NANOS;
        while (!BAKE.isEmpty() && System.nanoTime() < deadline) {
            BakeJob job = BAKE.pollFirst();
            if (job == null || job.chunk().isReleased()) {
                continue;
            }
            MirageSectionBaker.bake(job.chunk(), job.sectionIndex());
            bakedSections++;
        }
    }

    /** Nearest active seam for the current camera X, or null. */
    @Nullable
    public static ActiveSeam nearestSeam(double cameraX) {
        Seam best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Seam seam : SEAMS.values()) {
            if (!seam.active()) {
                continue;
            }
            double distance = Math.abs(seam.slabX - cameraX);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = seam;
            }
        }
        if (best != null) {
            return new ActiveSeam(best.slabX, best.westLane, best.eastLane, bestDistance);
        }
        return membraneSeam(cameraX);
    }

    /**
     * Phase-1 membrane only: no lane packet has arrived yet (or the world predates the seam
     * protocol), so use the nearest band boundary and the band sync's lane pair. Display only —
     * the lane names feed the tint, never a request or a mask.
     */
    @Nullable
    private static ActiveSeam membraneSeam(double cameraX) {
        if (BandRiftMode.current() == BandRiftMode.RIFT) {
            return null;
        }
        int bandSize = ClientBandProgress.bandSize();
        if (bandSize <= 0) {
            return null;
        }
        int west = Math.floorDiv(Mth.floor(cameraX), bandSize) * bandSize;
        int east = west + bandSize;
        int slabX = Math.abs(cameraX - west) <= Math.abs(east - cameraX) ? west : east;
        String current = ClientBandLane.lane();
        String outward = ClientBandLane.nextLane();
        String other = outward == null || outward.isEmpty() ? "unknown" : outward;
        String westLane = cameraX < slabX ? current : other;
        String eastLane = cameraX < slabX ? other : current;
        return new ActiveSeam(slabX, westLane, eastLane, Math.abs(slabX - cameraX));
    }

    /**
     * Draws one side's baked sections.
     *
     * @return the number of vertex buffers drawn
     */
    public static int renderSide(
            int slabX, byte side, int riftMode, int layer,
            Matrix4f modelView, Matrix4f projection, Vec3 camera, int lightmapTextureId) {
        Side buffer = SIDES.get(sideKey(slabX, side));
        if (buffer == null || buffer.chunks.isEmpty()) {
            return 0;
        }
        ShaderInstance shader = BandRiftShaders.mirageTerrain();
        if (shader == null) {
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
        MirageUniforms.apply(shader, riftMode, layer == MirageSectionMesh.LAYER_CUTOUT ? 0.1F : 0.0F,
                buffer, slabX, side, camera);
        int blockAtlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
        shader.setSampler("Sampler0", blockAtlas);
        shader.setSampler("Sampler2", lightmapTextureId > 0 ? lightmapTextureId : RenderSystem.getShaderTexture(2));
        int draws = 0;
        shader.apply();
        try {
            Uniform origin = shader.getUniform("u_MirageOrigin");
            for (ClientMirageChunk chunk : buffer.chunks.values()) {
                if (chunk.isReleased()) {
                    continue;
                }
                for (int sectionIndex = 0; sectionIndex < chunk.sectionCount(); sectionIndex++) {
                    MirageSectionMesh mesh = chunk.mesh(sectionIndex);
                    if (mesh == null || !mesh.has(layer)) {
                        continue;
                    }
                    if (origin != null) {
                        origin.set(
                                (float) (mesh.originX() - camera.x),
                                (float) (mesh.originY() - camera.y),
                                (float) (mesh.originZ() - camera.z));
                        origin.upload();
                    }
                    if (mesh.draw(layer)) {
                        draws++;
                        VolumetricRiftState.noteFarDraw();
                    }
                }
            }
        } finally {
            shader.clear();
        }
        return draws;
    }

    /**
     * Feather bounds of the tableau actually loaded for {@code (slabX, side)}: {@code x} = blocks
     * of mirage past the seam (ellipse semi-axis), {@code y} = centre Z (camera relative),
     * {@code z} = half width in blocks (the other semi-axis), {@code w} = feather width. Only the
     * far-side tableau (mode 3) feathers; the cavity mirage keeps {@code w = 0} so it never fogs
     * toward the sky.
     */
    private static void boundsOf(Side buffer, int slabX, byte side, Vec3 camera, int riftMode, float[] out) {
        out[0] = 1.0E5F;
        out[1] = 0.0F;
        out[2] = 1.0E5F;
        out[3] = 0.0F;
        if (riftMode != VolumetricRiftMath.MODE_NEAR_MIRAGE || buffer == null || buffer.chunks.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : buffer.chunks.keySet()) {
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            minX = Math.min(minX, chunkX);
            maxX = Math.max(maxX, chunkX);
            minZ = Math.min(minZ, chunkZ);
            maxZ = Math.max(maxZ, chunkZ);
        }
        int seamChunkX = slabX >> 4;
        int depthChunks = side == MirageChunkKey.WEST_BAND_INTO_EAST
                ? maxX - seamChunkX + 1 : seamChunkX - minX;
        out[0] = MirageRequestPlanner.depthBlocks(Math.max(1, depthChunks));
        out[1] = (minZ + maxZ + 1) * 8.0F - (float) camera.z;
        out[2] = (maxZ - minZ + 1) * 8.0F;
        out[3] = MirageUniforms.EDGE_FEATHER;
    }

    /** Lazily bound uniforms of {@code dimblend:mirage_terrain}; re-resolved on shader reload. */
    private static final class MirageUniforms {
        /** Feather width of the tableau's elliptical boundary, in blocks. */
        private static final float EDGE_FEATHER = 24.0F;
        private static final float[] BOUNDS = new float[4];

        private static ShaderInstance cached;
        private static Uniform mode;
        private static Uniform alphaCutoff;
        private static Uniform plane;
        private static Uniform center;
        private static Uniform radii;
        private static Uniform style;
        private static Uniform cam;
        private static Uniform bounds;

        static void apply(ShaderInstance shader, int riftMode, float cutoff,
                          Side buffer, int slabX, byte side, Vec3 camera) {
            if (shader != cached) {
                cached = shader;
                mode = shader.getUniform("u_RiftMode");
                alphaCutoff = shader.getUniform("u_AlphaCutoff");
                plane = shader.getUniform("u_RiftPlane");
                center = shader.getUniform("u_RiftCenter");
                radii = shader.getUniform("u_RiftRadii");
                style = shader.getUniform("u_RiftStyle");
                cam = shader.getUniform("u_RiftCam");
                bounds = shader.getUniform("u_MirageBounds");
            }
            if (mode != null) {
                mode.set(riftMode);
            }
            if (alphaCutoff != null) {
                alphaCutoff.set(cutoff);
            }
            if (bounds != null) {
                boundsOf(buffer, slabX, side, camera, riftMode, BOUNDS);
                bounds.set(BOUNDS[0], BOUNDS[1], BOUNDS[2], BOUNDS[3]);
            }
            float[] planeValue = VolumetricRiftState.plane();
            if (plane != null) {
                plane.set(planeValue[0], planeValue[1], planeValue[2], planeValue[3]);
            }
            float[] centerValue = VolumetricRiftState.center();
            if (center != null) {
                center.set(centerValue[0], centerValue[1], centerValue[2], centerValue[3]);
            }
            float[] radiiValue = VolumetricRiftState.radii();
            if (radii != null) {
                radii.set(radiiValue[0], radiiValue[1], radiiValue[2], radiiValue[3]);
            }
            float[] styleValue = VolumetricRiftState.style();
            if (style != null) {
                style.set(styleValue[0], styleValue[1], styleValue[2], styleValue[3]);
            }
            float[] camValue = VolumetricRiftState.cam();
            if (cam != null) {
                cam.set(camValue[0], camValue[1], camValue[2], camValue[3]);
            }
        }
    }

    // ---- lifecycle + diagnostics ---------------------------------------------------------------

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        clearAll();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
    }

    private static void clearAll() {
        for (Side side : SIDES.values()) {
            for (ClientMirageChunk chunk : side.chunks.values()) {
                chunk.release();
            }
            side.chunks.clear();
        }
        SIDES.clear();
        SEAMS.clear();
        BAKE.clear();
        lastPayloadTick = 0L;
        BandLightTexture.close();
        BandLightTexture.restore();
    }

    @Nullable
    public static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    @Nullable
    public static ClientMirageChunk find(byte side, int slabX, int chunkX, int chunkZ) {
        Side buffer = SIDES.get(sideKey(slabX, side));
        return buffer == null ? null : buffer.chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** True when at least one chunk of that (seam, side) has arrived and is bakeable. */
    public static boolean sideHasChunks(int slabX, byte side) {
        Side buffer = SIDES.get(sideKey(slabX, side));
        return buffer != null && !buffer.chunks.isEmpty();
    }

    /** True when at least one section of that (seam, side) already has a baked buffer. */
    public static boolean sideHasGeometry(int slabX, byte side) {
        Side buffer = SIDES.get(sideKey(slabX, side));
        if (buffer == null) {
            return false;
        }
        for (ClientMirageChunk chunk : buffer.chunks.values()) {
            for (int sectionIndex = 0; sectionIndex < chunk.sectionCount(); sectionIndex++) {
                MirageSectionMesh mesh = chunk.mesh(sectionIndex);
                if (mesh != null && mesh.hasAny()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean rotating(@Nullable ClientLevel level) {
        return level != null && level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL);
    }

    public static int chunkCount(byte side) {
        int count = 0;
        for (long key : SIDES.keySet()) {
            if ((key & 3L) == (side == MirageChunkKey.WEST_BAND_INTO_EAST ? 1L : 2L)) {
                count += SIDES.get(key).chunks.size();
            }
        }
        return count;
    }

    public static int vboCount() {
        int count = 0;
        for (Side side : SIDES.values()) {
            for (ClientMirageChunk chunk : side.chunks.values()) {
                for (int sectionIndex = 0; sectionIndex < chunk.sectionCount(); sectionIndex++) {
                    MirageSectionMesh mesh = chunk.mesh(sectionIndex);
                    if (mesh == null) {
                        continue;
                    }
                    for (int layer = 0; layer < MirageSectionMesh.LAYERS; layer++) {
                        if (mesh.has(layer)) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    public static int queuedSections() {
        return BAKE.size();
    }

    public static String describe() {
        int seams = 0;
        for (Seam seam : SEAMS.values()) {
            if (seam.active()) {
                seams++;
            }
        }
        return String.format(Locale.ROOT,
                "mirageSeams=%d mirageWestChunks=%d mirageEastChunks=%d mirageVbos=%d mirageQueued=%d mirageBaked=%d payloads=%d requests=%d dropped=%d",
                seams, chunkCount(MirageChunkKey.WEST_BAND_INTO_EAST),
                chunkCount(MirageChunkKey.EAST_BAND_INTO_WEST), vboCount(), queuedSections(),
                bakedSections, payloads, requestsSent, discarded);
    }

    /** One-shot log used by the smoke test after a reload. */
    public static void logState() {
        LOGGER.info("[bandrift-mirage] {}", describe());
    }
}
