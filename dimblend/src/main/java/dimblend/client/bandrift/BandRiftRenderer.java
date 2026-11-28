package dimblend.client.bandrift;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dimblend.DimBlendRegistries;
import dimblend.client.ClientBandLane;
import dimblend.client.ClientBandProgress;
import dimblend.client.mirage.ClientMirageManager;
import dimblend.client.mirage.MirageSectionMesh;
import dimblend.mirage.MirageCapGeometry;
import dimblend.mirage.MirageChunkKey;
import dimblend.worldgen.OakTrackCorridor;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * The band seam, drawn three different ways depending on {@link BandRiftMode}.
 *
 * <h2>RIFT (default)</h2>
 * <p>S2 volumetric double universe. Each frame one seam is active: the nearest discovered seam
 * within {@link #seamActivationRadius()} blocks (render distance + 32, i.e. 224 blocks at RD 12)
 * whose near-side mirage has arrived. The frame is split into
 * four mutually exclusive mask quadrants (see {@link VolumetricRiftMath}):</p>
 * <ol>
 *   <li>{@code AFTER_SKY}: compute the aperture, publish the uniforms, set mode 1. The engine's own
 *       solid/cutout terrain pass then draws the near universe ({@code nearSide && !other}).</li>
 *   <li>{@code AFTER_SOLID_BLOCKS}: mirage pass 1 (mode 3) fills the far side with the near band's
 *       baked terrain. If the aperture is open, the far block runs under the far band's fog and
 *       lightmap: far sky, then direct {@code RenderSectionManager.renderLayer(SOLID/CUTOUT)} in
 *       mode 2. Mode returns to 0 so entities and block entities draw normally.</li>
 *   <li>{@code AFTER_BLOCK_ENTITIES}: mode 1 again, for the engine's translucent terrain pass.</li>
 *   <li>{@code AFTER_TRANSLUCENT_BLOCKS}: the same passes for the TRANSLUCENT layer only.</li>
 * </ol>
 * <p>Closed apertures ({@code open <= 0.002}) skip the whole far block: no far sky, no second
 * {@code renderLayer}. At 44..112 blocks the player then sees one seamless band on both sides of
 * the seam — no wall, no second band — for the cost of two fragment masks.</p>
 * <p>Because the aperture is pinned to the stationary seam plane, no near-side fragment can ever
 * belong to the far universe, so the {@code MODE_FAR_MIRAGE} draws are gone (they used to cost two
 * extra VBO loops per frame for nothing). The opposite-side chunks are still prefetched by
 * {@link ClientMirageManager} so crossing the seam and looking back is instant.</p>
 *
 * <h2>MEMBRANE / LEGACY</h2>
 * <p>MEMBRANE is the Phase-1 artefact (one batched octagonal membrane per visible seam, plus the
 * crossing burst); LEGACY draws nothing here and hands the gate column back to the vanilla
 * block-entity renderer. RIFT degrades to MEMBRANE automatically when Sodium is absent, the
 * shader injection missed, or the mirage/sky shaders failed to load.</p>
 *
 * <p>Nothing here depends on a {@code dimblend:warp_gate} block: W2 removed the wall, so seam
 * discovery is {@link ClientMirageManager}'s job (lane pair from the server, legacy block probe as
 * a fallback for old worlds).</p>
 */
public final class BandRiftRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Camera window where the Phase-1 membrane is drawn. */
    private static final double MEMBRANE_RADIUS = 96.0;
    private static final int FRAME_HISTORY = 120;
    private static final double MAX_TRACK_SPEED = 120.0;
    private static final double VELOCITY_RESPONSE = 12.0;
    private static final float BULGE_BLOCKS = 1.15F;
    private static final double CAVITY_RADIUS = 56.0;
    private static final long START_NANOS = System.nanoTime();

    @Nullable
    private static ClientMirageManager.ActiveSeam seam;
    private static final BandRiftSpring SPRING = new BandRiftSpring();
    private static double open;
    private static double solid;
    private static double glitch;
    private static double fade;
    private static boolean membraneChunkReady;
    private static boolean s2Active;
    private static double seamDistance;

    private static VertexBuffer membraneMesh;
    private static VertexBuffer glitchQuad;
    private static int screenTexture = -1;
    private static int screenFramebuffer = -1;
    private static int screenWidth;
    private static int screenHeight;

    private static ShaderInstance cachedRift;
    private static ShaderInstance cachedGlitch;
    private static Uniform riftExtent;
    private static Uniform riftOpen;
    private static Uniform riftFx;
    private static Uniform riftTint;
    private static Uniform glitchIntensity;
    private static Uniform glitchFlash;

    private static final float[] FRAME_MICROS = new float[FRAME_HISTORY];
    private static final float[] TINT = {0.42F, 0.78F, 1.0F};
    private static final float[] SABLE_BOX_X = new float[2];
    private static final Matrix4f GATE_MATRIX = new Matrix4f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    private static long lastFrameNanos;
    private static double frameDt;
    private static double lastCameraX = Double.NaN;
    private static double velocityX;
    private static double manualOpen = -1.0;
    private static double manualPulse;
    private static double lastBurst;
    private static double lastFlash;
    private static double lastIntensity;
    private static int lastStageNanos;
    private static int frameCursor;
    private static boolean sableRiding;
    private static float sableBoxMinX;
    private static float sableBoxMaxX;
    private static boolean loggedMissingUniforms;

    private BandRiftRenderer() {
    }

    /**
     * Camera window where S2 activates: the render distance in blocks plus 32, so the whole
     * visible half-space past the seam is covered instead of a fixed 176 blocks. At the old
     * constant the far band's {@code Y=128} stone wall popped in at 177..205 blocks once a
     * 12-chunk render distance (192+ blocks) could see it.
     */
    private static double seamActivationRadius() {
        return MirageCapGeometry.seamActivationRadius(ClientMirageManager.rdChunks());
    }

    /** Game-bus listener: every frame stage of one frame is handled here. */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            updateFrameDelta();
            prepareFrame(event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            renderSolidStage(event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            if (s2Active) {
                VolumetricRiftState.setMode(VolumetricRiftMath.MODE_NEAR_REAL);
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            renderTranslucentStage(event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            renderGlitch();
        }
    }

    /** Game-bus listener: forget per-level values; discovery itself lives in the mirage manager. */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active(Minecraft.getInstance().level)) {
            resetTransient();
        }
    }

    /** Game-bus listener: forget every per-level value (client level only — GL deletes run on render thread). */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        reset();
    }

    /** Game-bus listener: same reset on disconnect. */
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    // ---- command surface ---------------------------------------------------------------------

    /** {@code /dimblendrift status}: the seam state plus the fog transition. */
    public static List<String> statusLines() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT,
                "mode=%s seam=%s dist=%.1f open=%.3f solid=%.2f glitch=%.3f s2=%s frameUs=%.1f avgUs=%.1f maxUs=%.1f",
                BandRiftMode.current().id(),
                seam == null ? "none" : Integer.toString(seam.slabX()),
                seamDistance, open, solid, glitch, s2Active,
                lastStageNanos / 1000.0, averageMicros(), maxMicros()));
        lines.add(String.format(Locale.ROOT, "shaders=%s sodium=%s sable=%s burst=%.2f flash=%.2f",
                BandRiftShaders.status(), SodiumRiftBridge.describe(), sableStatus(), lastBurst, lastFlash));
        lines.add(BandFogTransition.statusLine());
        return lines;
    }

    /** {@code /dimblendrift diag}: every observable field the S2/M1 capture protocol asks for. */
    public static List<String> diagLines() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "mode=%s s2Active=%s slabX=%s",
                BandRiftMode.current().id(), s2Active,
                seam == null ? "none" : Integer.toString(seam.slabX())));
        lines.add(SodiumRiftBridge.describe());
        lines.add(VolumetricRiftState.describe());
        lines.add(String.format(Locale.ROOT, "westLane=%s eastLane=%s dist=%.1f open=%.3f",
                seam == null ? "?" : seam.westLane(),
                seam == null ? "?" : seam.eastLane(),
                seamDistance, open));
        lines.add(ClientMirageManager.describe());
        lines.add(String.format(Locale.ROOT,
                "farPassDraws=%d farFrameDraws=%d frameUs=%d shaders=%s bandLightUpdates=%d",
                VolumetricRiftState.farDraws(), VolumetricRiftState.farDrawsThisFrame(),
                VolumetricRiftState.lastFrameMicros(), BandRiftShaders.status(), BandLightTexture.updates()));
        lines.add(BandFogTransition.statusLine());
        return lines;
    }

    public static void setManualOpen(double value) {
        manualOpen = Mth.clamp(value, 0.0, 1.0);
    }

    public static void clearManualOpen() {
        manualOpen = -1.0;
    }

    /** Fires the crossing burst for the current seam, or a bare screen pulse when none is near. */
    public static void manualBurst() {
        if (seam != null) {
            SPRING.triggerBurst();
        }
        manualPulse = 1.0;
        LOGGER.info("[bandrift-burst] manual seam={}", seam == null ? "none" : seam.slabX());
    }

    // ---- frame preparation --------------------------------------------------------------------

    private static void prepareFrame(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (!active(level)) {
            return;
        }
        ClientMirageManager.tickBakeStep();
        Vec3 cameraPos = event.getCamera().getPosition();
        double cameraX = cameraPos.x;
        double velocity = measureVelocity(cameraX, frameDt);
        double dt = frameDt;
        manualPulse = manualPulse < 1.0E-4 ? 0.0 : manualPulse * Math.exp(-dt / 0.12);

        ClientMirageManager.ActiveSeam nearest = ClientMirageManager.nearestSeam(cameraX);
        if (nearest == null) {
            clearSeam();
            return;
        }
        if (seam == null || seam.slabX() != nearest.slabX()) {
            SPRING.reset();
        }
        seam = nearest;
        seamDistance = nearest.distance();
        // The seam plane is exactly X = slabX (the +0.5 used to belong to the old null-block wall
        // column). Crossing detection, shell distance and the aperture must all agree on it.
        double plane = nearest.slabX();
        double approach = approachSpeed(velocity, plane - cameraX);
        if (manualOpen >= 0.0) {
            SPRING.forceOpen(manualOpen);
            open = manualOpen;
        } else {
            open = SPRING.advance(seamDistance, approach, dt);
        }
        if (SPRING.updateBurst(cameraX, plane, dt)) {
            playCrossing(level, nearest.slabX());
            LOGGER.info("[bandrift-burst] slabX={} speed={}", nearest.slabX(),
                    String.format(Locale.ROOT, "%.1f", velocity));
        }
        solid = BandRiftSpring.solidScale(open);
        glitch = BandRiftSpring.glitchDensity(open);
        boolean tracked = sableTrackedBox(minecraft.player);
        boolean overlaps = tracked && SABLE_BOX_X[0] <= plane && SABLE_BOX_X[1] >= plane;
        double subLevelDistance = tracked
                ? Math.max(0.0, Math.max(SABLE_BOX_X[0] - plane, plane - SABLE_BOX_X[1]))
                : Double.POSITIVE_INFINITY;
        fade = BandRiftSpring.carriageFade(seamDistance, overlaps, subLevelDistance);
        membraneChunkReady = realChunksReady(level, nearest.slabX(), cameraX);

        double maxBurst = Math.max(manualPulse, SPRING.burstIntensity());
        lastBurst = maxBurst;
        lastFlash = Math.max(manualPulse, SPRING.flashIntensity());
        lastIntensity = Math.max(maxBurst, SPRING.burstIntensity());

        String nearLane = nearest.nearLane(cameraX);
        String farLane = nearest.farLane(cameraX);
        byte primarySide = cameraX < nearest.slabX()
                ? MirageChunkKey.WEST_BAND_INTO_EAST : MirageChunkKey.EAST_BAND_INTO_WEST;
        boolean tableauReady = ClientMirageManager.sideHasGeometry(nearest.slabX(), primarySide);
        boolean s2Capable = BandRiftMode.current() == BandRiftMode.RIFT
                && SodiumRiftBridge.available()
                && BandRiftShaders.s2Ready()
                && sodiumShadersInjected();
        boolean activate = s2Capable && seamDistance <= seamActivationRadius() && tableauReady;
        s2Active = activate;
        if (!activate) {
            VolumetricRiftState.deactivate();
            return;
        }
        float[] glow = glowForLane(farLane);
        double centerY = OakTrackCorridor.TRACK_Y
                + BandRiftGeometry.centerYBlocks(OakTrackCorridor.VAULT_FLOOR_DY, OakTrackCorridor.VAULT_APEX_DY);
        double radiusY = (BandRiftGeometry.halfHeightBlocks(OakTrackCorridor.VAULT_FLOOR_DY, OakTrackCorridor.VAULT_APEX_DY) + 1.5)
                * BULGE_BLOCKS;
        double radiusZ = (BandRiftGeometry.halfWidthBlocks() + 1.5) * BULGE_BLOCKS;
        double seconds = (System.nanoTime() - START_NANOS) / 1.0E9;
        // Off-track freedom: on the railway the shell hugs the vault; flying away from the corridor
        // it follows the camera instead of staying 40 blocks to the side of the player.
        VolumetricRiftMath.ShellCenter shellCenter = VolumetricRiftMath.shellCenter(
                cameraPos.y, cameraPos.z, centerY, OakTrackCorridor.CORRIDOR_Z + 0.5);
        VolumetricRiftMath.Params params = VolumetricRiftMath.volume(
                nearest.slabX(), cameraX, cameraPos.y, cameraPos.z,
                shellCenter.centerY(), shellCenter.centerZ(),
                open, radiusY, radiusZ, glitch, seconds, VolumetricRiftMath.RIM_WIDTH);
        VolumetricRiftState.activate(params, glow[0], glow[1], glow[2],
                nearest.slabX(), nearLane, farLane, seamDistance, open, glitch, seconds);
        VolumetricRiftState.setMode(VolumetricRiftMath.MODE_NEAR_REAL);
        VolumetricRiftState.setFarLaneDistance(seamDistance);
    }

    private static boolean sodiumShadersInjected() {
        // Before the first Sodium program is compiled nothing is known; keep the membrane as the
        // fallback until both stages report success. A tick later the flag flips and S2 takes over.
        return SodiumRiftBridge.vertexInjected && SodiumRiftBridge.fragmentInjected;
    }

    /**
     * True when the seam must be drawn the Phase-1 way because S2 cannot run at all: the mode is
     * MEMBRANE, or RIFT without Sodium / without the injected mask / without the mirage shaders.
     * A missing mirage payload is <em>not</em> a fallback condition — S2 simply stays inactive and
     * the world renders normally until the first chunks are baked.
     */
    private static boolean membraneFallback() {
        BandRiftMode mode = BandRiftMode.current();
        if (mode == BandRiftMode.MEMBRANE) {
            return true;
        }
        if (mode != BandRiftMode.RIFT) {
            return false;
        }
        return !SodiumRiftBridge.available()
                || !BandRiftShaders.s2Ready()
                || !sodiumShadersInjected();
    }

    // ---- S2 passes ----------------------------------------------------------------------------

    private static void renderSolidStage(RenderLevelStageEvent event) {
        if (BandRiftMode.current() != BandRiftMode.RIFT) {
            return;
        }
        if (!s2Active || seam == null) {
            return;
        }
        long start = System.nanoTime();
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f modelView = event.getModelViewMatrix();
        Matrix4f projection = event.getProjectionMatrix();
        byte primary = primarySide(camera.x);
        try (BandRiftRenderState guard = new BandRiftRenderState()) {
            applyMirageState(false);
            VolumetricRiftState.setMode(VolumetricRiftMath.MODE_NEAR_MIRAGE);
            int nearLight = nearLightmap();
            ClientMirageManager.renderSide(seam.slabX(), primary, VolumetricRiftMath.MODE_NEAR_MIRAGE,
                    MirageSectionMesh.LAYER_SOLID, modelView, projection, camera, nearLight);
            ClientMirageManager.renderSide(seam.slabX(), primary, VolumetricRiftMath.MODE_NEAR_MIRAGE,
                    MirageSectionMesh.LAYER_CUTOUT, modelView, projection, camera, nearLight);
            if (open <= VolumetricRiftMath.CLOSED_OPEN) {
                VolumetricRiftState.setMode(VolumetricRiftMath.MODE_OFF);
                return;
            }
            renderFarBlock(event, camera, modelView, projection, false);
        } finally {
            VolumetricRiftState.setMode(VolumetricRiftMath.MODE_OFF);
            lastStageNanos = (int) (System.nanoTime() - start);
            recordFrameMicros(lastStageNanos / 1000.0F);
        }
    }

    private static void renderTranslucentStage(RenderLevelStageEvent event) {
        if (membraneFallback()) {
            renderMembrane(event);
            return;
        }
        if (BandRiftMode.current() != BandRiftMode.RIFT) {
            return;
        }
        if (!s2Active || seam == null) {
            return;
        }
        long start = System.nanoTime();
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f modelView = event.getModelViewMatrix();
        Matrix4f projection = event.getProjectionMatrix();
        byte primary = primarySide(camera.x);
        try (BandRiftRenderState guard = new BandRiftRenderState()) {
            applyMirageState(true);
            VolumetricRiftState.setMode(VolumetricRiftMath.MODE_NEAR_MIRAGE);
            int nearLight = nearLightmap();
            ClientMirageManager.renderSide(seam.slabX(), primary, VolumetricRiftMath.MODE_NEAR_MIRAGE,
                    MirageSectionMesh.LAYER_TRANSLUCENT, modelView, projection, camera, nearLight);
            if (open <= VolumetricRiftMath.CLOSED_OPEN) {
                VolumetricRiftState.setMode(VolumetricRiftMath.MODE_OFF);
                return;
            }
            renderFarBlock(event, camera, modelView, projection, true);
        } finally {
            VolumetricRiftState.setMode(VolumetricRiftMath.MODE_OFF);
            lastStageNanos = (int) (System.nanoTime() - start);
            recordFrameMicros(lastStageNanos / 1000.0F);
        }
    }

    /** The far band's half of the aperture: sky, then the second terrain pass. */
    private static void renderFarBlock(
            RenderLevelStageEvent event, Vec3 camera, Matrix4f modelView, Matrix4f projection,
            boolean translucent) {
        String farLane = seam == null ? null : seam.farLane(camera.x);
        double seconds = (System.nanoTime() - START_NANOS) / 1.0E9;
        BandFogProfile.applyTemporary(BandFogProfile.forLane(farLane, BandFogProfile.renderDistanceBlocks()));
        BandLightTexture.bindForSodiumAndMirage(farLane);
        VolumetricRiftState.beginFarFrame();
        try {
            if (!translucent) {
                // The far sky must come before the far terrain so depth writes from the second pass
                // land on top of it; the quad itself writes no depth.
                BandFarSkyRenderer.render(event, farLane);
            }
            VolumetricRiftState.setMode(VolumetricRiftMath.MODE_FAR_REAL);
            int layer = translucent ? MirageSectionMesh.LAYER_TRANSLUCENT : VolumetricRiftState.LAYER_SOLID;
            SodiumRiftBridge.renderFarLayer(layer, projection, modelView,
                    camera.x, camera.y, camera.z);
            if (!translucent) {
                SodiumRiftBridge.renderFarLayer(VolumetricRiftState.LAYER_CUTOUT, projection, modelView,
                        camera.x, camera.y, camera.z);
            }
            // No MODE_FAR_MIRAGE pass any more: the aperture is pinned to X = slabX, so no fragment
            // on the near side can ever be reached through it and the far band's own chunks already
            // cover everything past the plane. The opposite-side mirage is still prefetched (see
            // ClientMirageManager) so crossing the seam and looking back needs no bake.
            // Keep the shader clock fresh inside the block so a long frame cannot desynchronise the
            // sky stars from the shell noise.
            VolumetricRiftState.setSeconds((float) seconds);
        } finally {
            BandLightTexture.restore();
            BandFogProfile.restore();
            VolumetricRiftState.endFarFrame();
        }
    }

    /** Camera side: {@code +1} = west of the seam (west band into east columns). */
    private static byte primarySide(double cameraX) {
        int slabX = seam == null ? 0 : seam.slabX();
        return cameraX < slabX ? MirageChunkKey.WEST_BAND_INTO_EAST : MirageChunkKey.EAST_BAND_INTO_WEST;
    }

    private static int nearLightmap() {
        int main = SodiumRiftBridge.mainLightmap();
        if (main > 0) {
            return main;
        }
        return BandLightTexture.textureId(seam == null ? null : seam.nearLane(
                Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().x));
    }

    /** Opaque mirage state: depth write, cull; translucent: blend, no depth write, no cull. */
    private static void applyMirageState(boolean translucent) {
        RenderSystem.enableDepthTest();
        GlStateManager._depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);
        if (translucent) {
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.disableCull();
        } else {
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
        }
    }

    // ---- Phase-1 membrane ---------------------------------------------------------------------

    private static void renderMembrane(RenderLevelStageEvent event) {
        ShaderInstance shader = BandRiftShaders.rift();
        Minecraft minecraft = Minecraft.getInstance();
        if (shader == null || !active(minecraft.level) || seam == null) {
            return;
        }
        if (seamDistance > MEMBRANE_RADIUS || fade <= 0.002) {
            return;
        }
        bindRiftUniforms(shader);
        updateTint();
        Vec3 camera = event.getCamera().getPosition();
        long frameStart = System.nanoTime();
        try (BandRiftRenderState guard = new BandRiftRenderState()) {
            guard.applyMembrane();
            try {
                GATE_MATRIX.set(event.getModelViewMatrix()).translate(
                        (float) (seam.slabX() + 0.5 - camera.x),
                        (float) (OakTrackCorridor.TRACK_Y
                                + BandRiftGeometry.centerYBlocks(OakTrackCorridor.VAULT_FLOOR_DY, OakTrackCorridor.VAULT_APEX_DY)
                                - camera.y),
                        (float) (OakTrackCorridor.CORRIDOR_Z + 0.5 - camera.z));
                set2(riftExtent,
                        (float) BandRiftGeometry.halfHeightBlocks(OakTrackCorridor.VAULT_FLOOR_DY, OakTrackCorridor.VAULT_APEX_DY),
                        (float) BandRiftGeometry.halfWidthBlocks());
                set4(riftOpen, (float) open, (float) solid, (float) glitch, (float) fade);
                set4(riftFx, membraneChunkReady ? 1.0F : 0.0F, (float) SPRING.burstIntensity(),
                        Math.floorMod(seam.slabX(), 61) * 0.0173F,
                        camera.x <= seam.slabX() + 0.5 ? BULGE_BLOCKS : -BULGE_BLOCKS);
                set4(riftTint, TINT[0], TINT[1], TINT[2], 1.0F);
                VertexBuffer mesh = membraneMesh();
                if (mesh != null) {
                    mesh.bind();
                    try {
                        mesh.drawWithShader(GATE_MATRIX, event.getProjectionMatrix(), shader);
                    } finally {
                        VertexBuffer.unbind();
                    }
                }
            } finally {
                shader.clear();
            }
        }
        lastStageNanos = (int) (System.nanoTime() - frameStart);
        recordFrameMicros(lastStageNanos / 1000.0F);
    }

    private static void bindRiftUniforms(ShaderInstance shader) {
        if (shader == cachedRift) {
            return;
        }
        cachedRift = shader;
        riftExtent = shader.getUniform("RiftExtent");
        riftOpen = shader.getUniform("RiftOpen");
        riftFx = shader.getUniform("RiftFx");
        riftTint = shader.getUniform("RiftTint");
        if (riftExtent == null || riftOpen == null || riftFx == null || riftTint == null) {
            if (!loggedMissingUniforms) {
                loggedMissingUniforms = true;
                LOGGER.warn("[bandrift] band_rift uniforms missing (RiftExtent={} RiftOpen={} RiftFx={} RiftTint={}); "
                                + "the membrane will render with the JSON defaults",
                        riftExtent != null, riftOpen != null, riftFx != null, riftTint != null);
            }
        }
    }

    @Nullable
    private static VertexBuffer membraneMesh() {
        if (membraneMesh != null) {
            return membraneMesh;
        }
        BandRiftGeometry.Mesh mesh = BandRiftGeometry.build(
                OakTrackCorridor.VAULT_FLOOR_DY, OakTrackCorridor.VAULT_APEX_DY);
        if (mesh.isEmpty()) {
            return null;
        }
        BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < mesh.vertexCount(); i++) {
            BandRiftGeometry.Vertex vertex = mesh.vertex(i);
            builder.addVertex(0.0F, vertex.yBlocks(), vertex.zBlocks())
                    .setUv(vertex.u(), vertex.v())
                    .setColor(255, 255, 255, 255);
        }
        MeshData data = builder.build();
        if (data == null) {
            return null;
        }
        VertexBuffer created = new VertexBuffer(VertexBuffer.Usage.STATIC);
        created.bind();
        try {
            created.upload(data);
        } finally {
            VertexBuffer.unbind();
        }
        membraneMesh = created;
        return created;
    }

    private static boolean realChunksReady(ClientLevel level, int slabX, double cameraX) {
        int direction = cameraX >= slabX ? -1 : 1;
        int chunkZ = OakTrackCorridor.CORRIDOR_Z >> 4;
        return level.getChunkSource().hasChunk((slabX + direction * 16) >> 4, chunkZ)
                && level.getChunkSource().hasChunk((slabX + direction * 32) >> 4, chunkZ);
    }

    // ---- glitch pass -------------------------------------------------------------------------

    private static void renderGlitch() {
        if (BandRiftMode.current() == BandRiftMode.LEGACY) {
            return;
        }
        double intensity = lastIntensity;
        double flash = lastFlash;
        if (intensity <= 0.001 && flash <= 0.001) {
            return;
        }
        ShaderInstance shader = BandRiftShaders.glitch();
        if (shader == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getMainRenderTarget().width;
        int height = minecraft.getMainRenderTarget().height;
        VertexBuffer quad = glitchQuad();
        if (width <= 0 || height <= 0 || quad == null || !ensureScreenTarget(width, height)) {
            return;
        }
        bindGlitchUniforms(shader);
        int levelFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try (BandRiftRenderState guard = new BandRiftRenderState()) {
            guard.applyGlitch();
            try {
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, levelFramebuffer);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, screenFramebuffer);
                GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, levelFramebuffer);
                RenderSystem.viewport(0, 0, width, height);
                set1(glitchIntensity, (float) intensity);
                set1(glitchFlash, (float) flash);
                shader.setDefaultUniforms(VertexFormat.Mode.QUADS, IDENTITY, IDENTITY, minecraft.getWindow());
                if (shader.COLOR_MODULATOR != null) {
                    shader.COLOR_MODULATOR.set(1.0F, 1.0F, 1.0F, 1.0F);
                }
                if (shader.SCREEN_SIZE != null) {
                    shader.SCREEN_SIZE.set((float) width, (float) height);
                }
                shader.setSampler("Sampler0", screenTexture);
                shader.apply();
                try {
                    quad.bind();
                    try {
                        quad.draw();
                    } finally {
                        VertexBuffer.unbind();
                    }
                } finally {
                    shader.clear();
                }
            } finally {
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            }
        }
    }

    private static void bindGlitchUniforms(ShaderInstance shader) {
        if (shader == cachedGlitch) {
            return;
        }
        cachedGlitch = shader;
        glitchIntensity = shader.getUniform("Intensity");
        glitchFlash = shader.getUniform("Flash");
        if (glitchIntensity == null || glitchFlash == null) {
            LOGGER.warn("[bandrift] band_glitch uniforms missing (Intensity={} Flash={})",
                    glitchIntensity != null, glitchFlash != null);
        }
    }

    private static boolean ensureScreenTarget(int width, int height) {
        if (screenTexture != -1 && screenWidth == width && screenHeight == height) {
            return true;
        }
        releaseScreenTarget();
        int previousActive = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        screenTexture = GlStateManager._genTexture();
        GlStateManager._bindTexture(screenTexture);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._bindTexture(previousTexture);
        GlStateManager._activeTexture(previousActive);

        screenFramebuffer = GlStateManager.glGenFramebuffers();
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, screenFramebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, screenTexture, 0);
        int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.warn("[bandrift] colour copy target incomplete (status 0x{}), crossing effect disabled",
                    Integer.toHexString(status));
            releaseScreenTarget();
            return false;
        }
        screenWidth = width;
        screenHeight = height;
        return true;
    }

    private static void releaseScreenTarget() {
        if (screenTexture != -1) {
            GlStateManager._deleteTexture(screenTexture);
            screenTexture = -1;
        }
        if (screenFramebuffer != -1) {
            GlStateManager._glDeleteFramebuffers(screenFramebuffer);
            screenFramebuffer = -1;
        }
        screenWidth = 0;
        screenHeight = 0;
    }

    private static VertexBuffer glitchQuad() {
        if (glitchQuad != null) {
            return glitchQuad;
        }
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.addVertex(-1.0F, -1.0F, 0.0F).setUv(0.0F, 0.0F).setColor(255, 255, 255, 255);
        builder.addVertex(1.0F, -1.0F, 0.0F).setUv(1.0F, 0.0F).setColor(255, 255, 255, 255);
        builder.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F).setColor(255, 255, 255, 255);
        builder.addVertex(-1.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F).setColor(255, 255, 255, 255);
        MeshData data = builder.build();
        if (data == null) {
            return null;
        }
        glitchQuad = new VertexBuffer(VertexBuffer.Usage.STATIC);
        glitchQuad.bind();
        try {
            glitchQuad.upload(data);
        } finally {
            VertexBuffer.unbind();
        }
        return glitchQuad;
    }

    // ---- audio / tint / helpers --------------------------------------------------------------

    private static void playCrossing(ClientLevel level, int slabX) {
        double x = slabX + 0.5;
        double y = OakTrackCorridor.TRACK_Y + 0.5;
        double z = OakTrackCorridor.CORRIDOR_Z + 0.5;
        level.playLocalSound(x, y, z, SoundEvents.PORTAL_TRIGGER, SoundSource.AMBIENT, 0.55F, 0.72F, false);
        level.playLocalSound(x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 0.35F, 1.35F, false);
    }

    /** Glow colour of the far band, reused by the shell rim and the far sky. */
    private static float[] glowForLane(@Nullable String lane) {
        switch (lane == null ? "" : lane) {
            case "nether" -> tint(1.00F, 0.45F, 0.22F);
            case "underground" -> tint(0.95F, 0.62F, 0.24F);
            case "end" -> tint(0.72F, 0.45F, 1.00F);
            case "deeperdarker" -> tint(0.45F, 0.35F, 1.00F);
            case "twilight" -> tint(0.55F, 1.00F, 0.72F);
            case "starlight" -> tint(1.00F, 0.88F, 0.55F);
            case "voidscape" -> tint(0.62F, 0.35F, 1.00F);
            case "aether" -> tint(0.85F, 0.95F, 1.00F);
            default -> tint(0.42F, 0.78F, 1.00F);
        }
        return TINT;
    }

    /** Near-band tint for the membrane (the band the player is standing in). */
    private static void updateTint() {
        String lane = ClientBandLane.lane();
        glowForLane(lane);
    }

    private static void tint(float red, float green, float blue) {
        TINT[0] = red;
        TINT[1] = green;
        TINT[2] = blue;
    }

    private static double approachSpeed(double velocity, double offset) {
        if (offset == 0.0) {
            return Math.abs(velocity);
        }
        return Math.max(0.0, velocity * Math.signum(offset));
    }

    private static double measureVelocity(double cameraX, double dt) {
        if (Double.isNaN(lastCameraX)) {
            lastCameraX = cameraX;
            return 0.0;
        }
        double instant = dt > 0.0 ? (cameraX - lastCameraX) / dt : velocityX;
        lastCameraX = cameraX;
        if (!Double.isFinite(instant)) {
            return velocityX;
        }
        instant = Mth.clamp(instant, -MAX_TRACK_SPEED, MAX_TRACK_SPEED);
        velocityX += (instant - velocityX) * Math.min(1.0, dt * VELOCITY_RESPONSE);
        return velocityX;
    }

    private static void updateFrameDelta() {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        frameDt = BandRiftSpring.sanitizeDt(dt);
    }

    private static boolean active(@Nullable ClientLevel level) {
        return level != null
                && level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)
                && ClientBandProgress.bandSize() > 0;
    }

    private static void clearSeam() {
        seam = null;
        seamDistance = 0.0;
        open = 0.0;
        solid = 0.0;
        glitch = 0.0;
        fade = 0.0;
        s2Active = false;
        SPRING.resetOpening();
        VolumetricRiftState.deactivate();
    }

    private static void resetTransient() {
        clearSeam();
        SPRING.reset();
    }

    private static void closeMeshes() {
        if (membraneMesh != null) {
            membraneMesh.close();
            membraneMesh = null;
        }
        if (glitchQuad != null) {
            glitchQuad.close();
            glitchQuad = null;
        }
        BandFarSkyRenderer.close();
        BandLightTexture.close();
        releaseScreenTarget();
        cachedRift = null;
        cachedGlitch = null;
    }

    private static void reset() {
        resetTransient();
        // Both events that reach this run on the client thread, i.e. the render thread: GL
        // deletes are safe here and cannot leak until the next level loads.
        closeMeshes();
        lastCameraX = Double.NaN;
        velocityX = 0.0;
        lastFrameNanos = 0L;
        frameDt = 0.0;
        manualOpen = -1.0;
        manualPulse = 0.0;
        lastBurst = 0.0;
        lastFlash = 0.0;
        lastIntensity = 0.0;
        lastStageNanos = 0;
        sableRiding = false;
        sableBoxMinX = 0.0F;
        sableBoxMaxX = 0.0F;
        java.util.Arrays.fill(FRAME_MICROS, 0.0F);
        frameCursor = 0;
    }

    private static void recordFrameMicros(float micros) {
        FRAME_MICROS[frameCursor] = micros;
        frameCursor = (frameCursor + 1) % FRAME_HISTORY;
    }

    private static double averageMicros() {
        double sum = 0.0;
        int count = 0;
        for (float value : FRAME_MICROS) {
            if (value > 0.0F) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double maxMicros() {
        double max = 0.0;
        for (float value : FRAME_MICROS) {
            max = Math.max(max, value);
        }
        return max;
    }

    /** Lazily loaded Sable bridge: the companion jar is JiJ'd, but Sable itself is optional. */
    private static boolean sableTrackedBox(@Nullable LocalPlayer player) {
        sableRiding = false;
        if (player == null || !player.isAlive()) {
            return false;
        }
        if (!SableBridge.present()) {
            return false;
        }
        if (!SableBridge.boxX(player, SABLE_BOX_X)) {
            return false;
        }
        sableRiding = true;
        sableBoxMinX = SABLE_BOX_X[0];
        sableBoxMaxX = SABLE_BOX_X[1];
        return true;
    }

    private static String sableStatus() {
        if (!SableBridge.present()) {
            return "absent";
        }
        if (!sableRiding) {
            return "not-riding";
        }
        return String.format(Locale.ROOT, "riding x=%.1f..%.1f", sableBoxMinX, sableBoxMaxX);
    }

    private static void set1(@Nullable Uniform uniform, float value) {
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void set2(@Nullable Uniform uniform, float first, float second) {
        if (uniform != null) {
            uniform.set(first, second);
        }
    }

    private static void set4(@Nullable Uniform uniform, float first, float second, float third, float fourth) {
        if (uniform != null) {
            uniform.set(first, second, third, fourth);
        }
    }

    /**
     * The only class that touches Sable companion types; loaded on first use, behind the
     * {@code ModList} check, so a pack without Sable never initialises it.
     */
    private static final class SableBridge {
        private static boolean checked;
        private static boolean present;

        private SableBridge() {
        }

        static boolean present() {
            if (!checked) {
                ModList mods = ModList.get();
                if (mods == null) {
                    return false;
                }
                present = mods.isLoaded("sable");
                checked = true;
            }
            return present;
        }

        static boolean boxX(LocalPlayer player, float[] out) {
            dev.ryanhcode.sable.companion.SubLevelAccess subLevel =
                    dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player);
            if (subLevel == null) {
                return false;
            }
            dev.ryanhcode.sable.companion.math.BoundingBox3dc box = subLevel.boundingBox();
            out[0] = (float) box.minX();
            out[1] = (float) box.maxX();
            return true;
        }
    }
}
