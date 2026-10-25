package dimblend.experience.client.headlight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSettings;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;

/**
 * Lit-lava highlight: a pulsing orange outline around the top surface of the lava a headlight beam reaches.
 * Only registered with the rest of the headlight renderer (sable + veil present, iris absent), and only lit
 * while that renderer is: no beam, no highlight.
 *
 * <p><b>Recompute schedule.</b> The world scan runs off the render loop, at most every
 * {@link #MIN_INTERVAL_TICKS} client ticks (10 Hz) and only when a beam actually moved: lens quantized to
 * {@link #POSE_STEP} blocks and axis to {@link #DIRECTION_STEP}. A train at 20 m/s moves 2 blocks between
 * updates, so the lit edge steps forward in 2-block jumps at 10 Hz and never lags more than that; stationary
 * lamps are re-scanned only for the {@link #MAX_INTERVAL_TICKS} idle refresh, which picks up placed/removed
 * lava, world edits in the beam and chunks arriving. With the default beam the scan walks ~12k voxels of the
 * cone box but only the lava-bearing sections of it (palette-pruned), i.e. an estimated ~0.1-0.3 ms on the
 * client thread; the hard {@link HeadlightLavaScan#VOXEL_BUDGET} caps a pathological scene and
 * {@code -Ddimblend.headlight.diag=true} logs the measured cost.
 *
 * <p><b>Drawing.</b> Two passes at {@code AFTER_TRANSLUCENT_BLOCKS}, both over the same edge list: first a
 * see-through pass (no depth test, no depth write, main-target output) at
 * {@link HeadlightLavaMath#HIDDEN_ALPHA_FACTOR} of the pulse alpha, then the ordinary depth-tested
 * {@code RenderType.lines()} pass at the full pulse alpha. Camera-visible segments stay bright; segments
 * behind the cab, terrain or a wall keep a dim outline instead of disappearing. The lava top face is opaque
 * terrain written in the solid chunk layer ({@code LevelRenderer.java:955-957}), so both passes come after it,
 * but the depth-tested pass must also come after translucent blocks so water and stained glass in front can
 * hide the bright line (the dim line is still drawn behind them). {@code RenderType.lines()}
 * ({@code RenderType.java:599-613}) is LEQUAL depth-tested, blends, and writes depth; under Fabulous its
 * {@code ITEM_ENTITY_TARGET} output ({@code RenderStateShard.java:339-347}) hands the segments to the
 * transparency post chain, which composites them depth-sorted against the final main depth
 * ({@code transparency.fsh}), so nearer terrain, vehicles and translucent surfaces still cover the bright
 * line. The see-through type and why it writes to main (rather than {@code ITEM_ENTITY_TARGET}) are
 * documented in {@link LavaOutlineRenderTypes}. Both batches are ended here; the render types own their
 * state, so nothing outside them is changed.
 */
public final class HeadlightLavaHighlight {
    /** Floor between scans: 10 Hz keeps a moving beam's lit edge within two blocks of the light. */
    private static final int MIN_INTERVAL_TICKS = 2;
    /** Idle refresh: world edits and chunks are picked up within a second even when nothing moves. */
    private static final int MAX_INTERVAL_TICKS = 20;
    /** A lens moving less than this and an axis turning less than {@link #DIRECTION_STEP} reuse the last scan. */
    private static final double POSE_STEP = 0.5;
    private static final double DIRECTION_STEP = 1.0 / 64.0;
    /** Same opt-in switch as {@link HeadlightDiagnostics}: logs the scan's CPU cost, throttled. */
    private static final boolean DIAGNOSTIC = Boolean.getBoolean("dimblend.headlight.diag");
    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<LavaEdge> edges = List.of();
    private static int surfaces;
    private static int ticks;
    private static int idleTicks;
    private static long signature;
    private static boolean valid;
    private static long nextLog;

    /** Client tick: refresh the lit set for the beams the renderer would draw this frame. */
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !active()) {
            clear();
            return;
        }
        if (++ticks < MIN_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double range = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_RANGE);
        double angle = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_ANGLE);
        double intensity = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_INTENSITY);
        boolean occlusion = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_OCCLUSION) == 1.0;
        // No frustum: the set must not pop when the camera turns, and the segments are depth-tested anyway.
        List<HeadlightBeam> beams = HeadlightCollector.collect(mc.level, camera, null, range);
        if (beams.isEmpty()) {
            clear();
            return;
        }
        long next = signature(beams, range, angle, intensity, occlusion);
        if (valid && next == signature) {
            idleTicks += MIN_INTERVAL_TICKS;
            if (idleTicks < MAX_INTERVAL_TICKS) {
                return;
            }
        }
        valid = true;
        signature = next;
        idleTicks = 0;
        long start = DIAGNOSTIC ? System.nanoTime() : 0L;
        HeadlightLavaScan.Result result = HeadlightLavaScan.scan(mc.level, beams, range, angle, intensity, occlusion);
        List<LavaSurface> lit = HeadlightLavaMath.capped(result.surfaces(), camera, HeadlightLavaMath.MAX_SURFACES);
        surfaces = lit.size();
        edges = HeadlightLavaMath.edges(lit);
        if (DIAGNOSTIC) diagnose(System.nanoTime() - start, result.reads(), surfaces);
    }

    /** Opt-in (-Ddimblend.headlight.diag=true) CPU timing of the scan, throttled like the GPU diagnostics. */
    private static void diagnose(long nanos, int reads, int faces) {
        long now = System.currentTimeMillis();
        if (now < nextLog) {
            return;
        }
        nextLog = now + 2000;
        LOGGER.info(String.format(Locale.ROOT, "[headlight-lava] %d faces, %d voxel reads, %.3f ms",
                faces, reads, nanos / 1_000_000.0));
    }

    /** Draws the outline; called at {@code AFTER_TRANSLUCENT_BLOCKS}, after the haze composite. */
    static void render(RenderLevelStageEvent event) {
        if (edges.isEmpty() || !active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        // The event pose is the identity here and the camera rotation lives in the model-view matrix, so world
        // positions go in relative to the camera, as vanilla draws its own world lines.
        PoseStack.Pose pose = event.getPoseStack().last();
        float alpha = HeadlightLavaMath.alpha(mc.level.getGameTime() + event.getCamera().getPartialTickTime());
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // First the see-through under-pass: every segment at the dimmed alpha, ignoring the depth buffer, so
        // the parts the depth-tested pass will hide still read as a faint outline through the occluder.
        VertexConsumer seeThrough = buffers.getBuffer(LavaOutlineRenderTypes.SEE_THROUGH_LINES);
        for (LavaEdge edge : edges) {
            append(seeThrough, pose, edge, camera, HeadlightLavaMath.hiddenAlpha(alpha));
        }
        buffers.endBatch(LavaOutlineRenderTypes.SEE_THROUGH_LINES);
        // Then the ordinary depth-tested pass: visible segments repaint the dim base at the full pulse alpha.
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (LavaEdge edge : edges) {
            append(lines, pose, edge, camera, alpha);
        }
        buffers.endBatch(RenderType.lines());
        // The vanilla line type's clear rebinds main; put the translucent layer's own target back so the
        // rest of the event and the layer's clear see the state they expect.
        LavaOutlineRenderTypes.restoreStageTarget();
    }

    /**
     * One segment as two vertices. The line shader offsets a vertex perpendicular to the screen-space
     * direction between {@code Position} and {@code Position + Normal} ({@code rendertype_lines.vsh}), so the
     * normal is the segment's own unit direction, exactly how vanilla feeds its line boxes.
     */
    private static void append(VertexConsumer lines, PoseStack.Pose pose, LavaEdge edge, Vec3 camera, float alpha) {
        float nx = (float) (edge.x2() - edge.x1());
        float ny = (float) (edge.y2() - edge.y1());
        float nz = (float) (edge.z2() - edge.z1());
        vertex(lines, pose, edge.x1(), edge.y1(), edge.z1(), nx, ny, nz, camera, alpha);
        vertex(lines, pose, edge.x2(), edge.y2(), edge.z2(), nx, ny, nz, camera, alpha);
    }

    private static void vertex(VertexConsumer lines, PoseStack.Pose pose, double x, double y, double z,
                               float nx, float ny, float nz, Vec3 camera, float alpha) {
        lines.addVertex(pose, (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
                .setColor(HeadlightLavaMath.RED, HeadlightLavaMath.GREEN, HeadlightLavaMath.BLUE, alpha)
                .setNormal(pose, nx, ny, nz);
    }

    /** Lit faces currently highlighted (debug command); 0 while the highlight is off. */
    static int surfaces() {
        return surfaces;
    }

    /** Forgets the scan; level unload, disconnect and the option going off. */
    static void clear() {
        edges = List.of();
        surfaces = 0;
        ticks = 0;
        idleTicks = 0;
        valid = false;
        signature = 0L;
    }

    private static boolean active() {
        return HeadlightRenderer.enabled()
                && TuningSettings.get(TuningOption.SABLE_HEADLIGHT_LAVA_HIGHLIGHT) == 1.0;
    }

    /**
     * Quantized fingerprint of everything a scan depends on, for the "did anything move" gate: the beam
     * poses, their order (nearest-eight selection), the photometry and the occlusion option.
     */
    private static long signature(List<HeadlightBeam> beams, double range, double angle, double intensity,
                                  boolean occlusion) {
        long hash = 0xCBF29CE484222325L;
        hash = mix(hash, beams.size());
        for (HeadlightBeam beam : beams) {
            hash = mix(hash, Math.round(beam.lens().x / POSE_STEP));
            hash = mix(hash, Math.round(beam.lens().y / POSE_STEP));
            hash = mix(hash, Math.round(beam.lens().z / POSE_STEP));
            hash = mix(hash, Math.round(beam.direction().x / DIRECTION_STEP));
            hash = mix(hash, Math.round(beam.direction().y / DIRECTION_STEP));
            hash = mix(hash, Math.round(beam.direction().z / DIRECTION_STEP));
        }
        hash = mix(hash, Double.doubleToLongBits(range));
        hash = mix(hash, Double.doubleToLongBits(angle));
        hash = mix(hash, Double.doubleToLongBits(intensity));
        return mix(hash, occlusion ? 1 : 0);
    }

    /** FNV-1a mixing: cheap, order-sensitive and stable across runs, which is all a change gate needs. */
    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001B3L;
    }

    private HeadlightLavaHighlight() {
    }
}
