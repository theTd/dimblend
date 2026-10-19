package dimblend.experience.client.headlight;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dimblend.experience.DimBlend;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSettings;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.slf4j.Logger;

/**
 * Surface lighting after block entities; after translucent terrain, a quarter-pixel-count haze volume and a
 * depth-aware additive composite that also draws the lens glare.
 */
public final class HeadlightRenderer {
    private static final ResourceLocation PROGRAM = ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "headlight/surface");
    private static final ResourceLocation FOG = ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "headlight/fog");
    private static final ResourceLocation OPAQUE_DISTANCE = ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "headlight/opaque_distance");
    private static final ResourceLocation FOG_COMPOSITE = ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "headlight/fog_composite");
    private static final ResourceLocation OCCLUSION_TRACE = ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "headlight/occlusion_trace");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HeadlightTargets TARGETS = new HeadlightTargets();
    private static boolean buffers;
    private static DebugView debug = DebugView.OFF;
    /** Beams handed from the surface stage to the haze stage of the same frame. */
    private static List<HeadlightBeam> fogBeams = List.of();

    private enum DebugView { OFF, ALBEDO, NORMAL, DEPTH, LIGHT, FOG, OCCLUSION }

    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(FrontlightIndex::onLoad);
        NeoForge.EVENT_BUS.addListener(FrontlightIndex::onUnload);
        NeoForge.EVENT_BUS.addListener(HeadlightOcclusion::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(HeadlightOcclusion::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(HeadlightRenderer::beforeFrame);
        NeoForge.EVENT_BUS.addListener(HeadlightRenderer::render);
        NeoForge.EVENT_BUS.addListener(HeadlightRenderer::commands);
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() == Minecraft.getInstance().level) reset();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> reset());
    }

    private static double value(TuningOption option) { return TuningSettings.get(option); }

    private static boolean enabled() {
        return Minecraft.getInstance().level != null && value(TuningOption.SABLE_HEADLIGHT) == 1.0
                && value(TuningOption.SABLE_HEADLIGHT_INTENSITY) > 0.0;
    }

    private static boolean fogEnabled() {
        return value(TuningOption.SABLE_HEADLIGHT_FOG) == 1.0
                && value(TuningOption.SABLE_HEADLIGHT_FOG_DENSITY) > 0.0;
    }

    private static boolean occlusionEnabled() {
        return value(TuningOption.SABLE_HEADLIGHT_OCCLUSION) == 1.0;
    }

    private static void beforeFrame(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        // Acquire before any world drawing/clearing, not halfway through the terrain pass.
        boolean active = enabled() && !HeadlightCollector.collect(mc.level,
                mc.gameRenderer.getMainCamera().getPosition(), null, value(TuningOption.SABLE_HEADLIGHT_RANGE)).isEmpty();
        if (active != buffers) {
            if (active) VeilRenderSystem.renderer().enableBuffers(PROGRAM, DynamicBufferType.ALBEDO, DynamicBufferType.NORMAL);
            else {
                VeilRenderSystem.renderer().disableBuffers(PROGRAM);
                TARGETS.close();
            }
            buffers = active;
        }
        if (active) TARGETS.prepareDynamicBuffers(mc.getMainRenderTarget());
        if (!active || !fogEnabled()) TARGETS.closeFog();
        // Off = no grids allocated or filled; they are rebuilt from chunk data when re-enabled.
        if ((!active || !occlusionEnabled()) && HeadlightOcclusion.allocated()) HeadlightOcclusion.close();
    }

    private static void reset() {
        HeadlightDiagnostics.close();
        FrontlightIndex.clear();
        HeadlightOcclusion.close();
        if (buffers) VeilRenderSystem.renderer().disableBuffers(PROGRAM);
        buffers = false;
        TARGETS.close();
        fogBeams = List.of();
        debug = DebugView.OFF;
    }

    private static void commands(RegisterClientCommandsEvent event) {
        var modes = Commands.literal("debug");
        for (DebugView mode : DebugView.values()) {
            modes.then(Commands.literal(mode.name().toLowerCase(java.util.Locale.ROOT)).executes(context -> {
                debug = mode;
                context.getSource().sendSuccess(() -> Component.literal("Headlight debug: " + mode.name().toLowerCase(java.util.Locale.ROOT)), false);
                return 1;
            }));
        }
        event.getDispatcher().register(Commands.literal("dbxheadlight").then(modes)
                .then(Commands.literal("list").executes(context -> listBeams(context.getSource())))
                // Dev iteration: re-reads resource packs so a loose pack can override the shaders.
                .then(Commands.literal("reload").executes(context -> {
                    Minecraft.getInstance().reloadResourcePacks();
                    return 1;
                })));
    }

    /** Dev aid: prints every active beam's world lens position and axis (chat and log) to frame shots. */
    private static int listBeams(net.minecraft.commands.CommandSourceStack source) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        List<HeadlightBeam> beams = HeadlightCollector.collect(mc.level, camera, null, value(TuningOption.SABLE_HEADLIGHT_RANGE));
        source.sendSuccess(() -> Component.literal("Headlight beams: " + beams.size()), false);
        for (int i = 0; i < beams.size(); i++) {
            var beam = beams.get(i);
            // Plot (sub-level) coordinates of the lens: where /setblock edits the vehicle itself.
            Vec3 plot = beam.owner() == null ? Vec3.ZERO : beam.owner().renderPose().transformPositionInverse(beam.lens());
            String line = String.format(java.util.Locale.ROOT, "#%d lens=(%.2f, %.2f, %.2f) dir=(%.3f, %.3f, %.3f) dist=%.1f plot=(%.2f, %.2f, %.2f)",
                    i, beam.lens().x, beam.lens().y, beam.lens().z,
                    beam.direction().x, beam.direction().y, beam.direction().z, beam.lens().distanceTo(camera),
                    plot.x, plot.y, plot.z);
            LOGGER.info("[headlight-list] {}", line);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return beams.size();
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            HeadlightDiagnostics.frame();
            fogBeams = List.of();
            renderSurface(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            List<HeadlightBeam> beams = fogBeams;
            fogBeams = List.of();
            try {
                renderHaze(event, beams);
            } finally {
                HeadlightDiagnostics.publish();
            }
        }
    }

    /** Surface light on opaque geometry, before translucency (water/glass then blend over lit ground). */
    private static void renderSurface(RenderLevelStageEvent event) {
        if (!buffers || !enabled()) return;
        var mc = Minecraft.getInstance();
        double range = value(TuningOption.SABLE_HEADLIGHT_RANGE);
        List<HeadlightBeam> beams = HeadlightCollector.collect(mc.level, event.getCamera().getPosition(), event.getFrustum(), range);
        HeadlightDiagnostics.beams(beams.size());
        if (beams.isEmpty()) {
            TARGETS.closeFog();
            return;
        }
        // Resolve the program every frame: Veil resource reload replaces it.
        ShaderProgram shader = VeilRenderSystem.renderer().getShaderManager().getShader(PROGRAM);
        if (shader == null && debug != DebugView.FOG) return;
        boolean haze = fogEnabled() && (debug == DebugView.OFF || debug == DebugView.FOG);
        try (var ignored = new HeadlightRenderState()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            TARGETS.prepare(mc.getMainRenderTarget());
            TARGETS.bindOutput();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.disableCull();
            HeadlightDiagnostics.beginSurface();
            try {
                traceOcclusion(event, beams, range);
            } finally {
                if (debug == DebugView.FOG) HeadlightDiagnostics.endSurface();
            }
            if (debug == DebugView.FOG) {
                TARGETS.bindOutput();
                TARGETS.clearOutput();
            } else {
                TARGETS.bindOutput();
                try {
                    if (debug == DebugView.OFF) additiveBlend();
                    else RenderSystem.disableBlend();
                    bindBeamUniforms(shader, event, beams, range);
                    shader.setTexture("SceneSampler", GL11.GL_TEXTURE_2D, TARGETS.sceneTexture());
                    shader.setTexture("DepthSampler", GL11.GL_TEXTURE_2D, mc.getMainRenderTarget().getDepthTextureId());
                    // Albedo/normal come from Veil's dynamic-buffer sources in the program JSON.
                    HeadlightOcclusion.bindLookup(shader, TARGETS.sceneTexture());
                    shader.bindSamplers(0);
                    shader.getUniformSafe("DebugView").setInt(debug.ordinal());
                    try {
                        VeilRenderSystem.drawScreenQuad();
                    } finally {
                        shader.removeTexture("SceneSampler");
                        shader.removeTexture("DepthSampler");
                        HeadlightOcclusion.unbindLookup(shader);
                    }
                } finally {
                    HeadlightDiagnostics.endSurface();
                }
            }
            // Haze waits for translucent terrain so it composites in front of water/glass instead of being painted
            // over. The march still runs to the opaque surface captured here; the part behind a translucent
            // surface is dimmed, so a beam seen through a window or under water does not vanish.
            if (haze && captureOpaqueDistance(event)) {
                fogBeams = beams;
            } else TARGETS.closeFog();
        }
    }

    /** Refreshes the occupancy grids and traces each beam's distance map (no-op with the option off). */
    private static void traceOcclusion(RenderLevelStageEvent event, List<HeadlightBeam> beams, double range) {
        HeadlightOcclusion.skip();
        if (!occlusionEnabled()) return;
        var mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        HeadlightOcclusion.update(mc.level, camera, beams, range);
        ShaderProgram trace = VeilRenderSystem.renderer().getShaderManager().getShader(OCCLUSION_TRACE);
        if (trace == null) {
            HeadlightDiagnostics.missingProgram(OCCLUSION_TRACE);
            return;
        }
        bindBeamUniforms(trace, event, beams, range);
        HeadlightOcclusion.trace(trace, camera, beams.size(), value(TuningOption.SABLE_HEADLIGHT_ANGLE));
    }

    private static boolean captureOpaqueDistance(RenderLevelStageEvent event) {
        ShaderProgram capture = VeilRenderSystem.renderer().getShaderManager().getShader(OPAQUE_DISTANCE);
        if (capture == null) {
            HeadlightDiagnostics.missingProgram(OPAQUE_DISTANCE);
            return false;
        }
        var main = Minecraft.getInstance().getMainRenderTarget();
        TARGETS.bindOpaque(main);
        RenderSystem.disableBlend();
        VeilRenderSystem.setShader(capture);
        capture.bind();
        capture.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP);
        capture.getUniformSafe("InverseProjection").setMatrix(new Matrix4f(event.getProjectionMatrix()).invert());
        capture.setTexture("DepthSampler", GL11.GL_TEXTURE_2D, main.getDepthTextureId());
        capture.bindSamplers(0);
        try {
            VeilRenderSystem.drawScreenQuad();
        } finally {
            capture.removeTexture("DepthSampler");
        }
        return true;
    }

    private static void renderHaze(RenderLevelStageEvent event, List<HeadlightBeam> beams) {
        if (beams.isEmpty() || !buffers || !enabled()) return;
        try (var ignored = new HeadlightRenderState()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.disableCull();
            HeadlightDiagnostics.beginFog();
            try {
                renderFog(event, beams, value(TuningOption.SABLE_HEADLIGHT_RANGE));
            } finally {
                HeadlightDiagnostics.endFog();
            }
        }
    }

    private static void additiveBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
    }

    private static void bindBeamUniforms(ShaderProgram shader, RenderLevelStageEvent event,
                                         List<HeadlightBeam> beams, double range) {
        VeilRenderSystem.setShader(shader);
        shader.bind();
        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP);
        shader.getUniformSafe("InverseProjection").setMatrix(new Matrix4f(event.getProjectionMatrix()).invert());
        shader.getUniformSafe("InverseView").setMatrix(new Matrix4f(event.getModelViewMatrix()).invert());
        shader.getUniformSafe("LampCount").setInt(beams.size());
        shader.getUniformSafe("Range").setFloat((float) range);
        shader.getUniformSafe("Intensity").setFloat((float) value(TuningOption.SABLE_HEADLIGHT_INTENSITY));
        double halfAngle = Math.toRadians(value(TuningOption.SABLE_HEADLIGHT_ANGLE) * 0.5);
        shader.getUniformSafe("ConeCosines").setVector((float) Math.cos(halfAngle), (float) Math.cos(halfAngle * 0.75));
        shader.getUniformSafe("FogRange").setVector(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
        shader.getUniformSafe("FogCylinder").setInt(RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? 1 : 0);
        for (int i = 0; i < beams.size(); i++) {
            var beam = beams.get(i);
            var relative = beam.lens().subtract(event.getCamera().getPosition());
            shader.getUniformSafe("LampPosition[" + i + "]").setVector((float) relative.x, (float) relative.y, (float) relative.z);
            shader.getUniformSafe("LampDirection[" + i + "]").setVector((float) beam.direction().x, (float) beam.direction().y, (float) beam.direction().z);
        }
    }

    private static void renderFog(RenderLevelStageEvent event, List<HeadlightBeam> beams, double range) {
        var manager = VeilRenderSystem.renderer().getShaderManager();
        ShaderProgram fog = manager.getShader(FOG);
        ShaderProgram composite = manager.getShader(FOG_COMPOSITE);
        if (fog == null || composite == null) {
            HeadlightDiagnostics.missingProgram(fog != null, composite != null);
            TARGETS.closeFog();
            return;
        }
        var main = Minecraft.getInstance().getMainRenderTarget();
        // Allocate only with enabled fog, visible beams and both programs available.
        TARGETS.bindFog(main);
        RenderSystem.disableBlend();
        bindBeamUniforms(fog, event, beams, range);
        fog.setTexture("DepthSampler", GL11.GL_TEXTURE_2D, main.getDepthTextureId());
        fog.setTexture("OpaqueSampler", GL11.GL_TEXTURE_2D, TARGETS.opaqueTexture());
        HeadlightOcclusion.bindLookup(fog, TARGETS.opaqueTexture());
        fog.bindSamplers(0);
        fog.getUniformSafe("Density").setFloat((float) value(TuningOption.SABLE_HEADLIGHT_FOG_DENSITY));
        fog.getUniformSafe("Samples").setInt((int) value(TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES));
        double maxDistance = 0;
        for (var beam : beams) maxDistance = Math.max(maxDistance, beam.lens().distanceTo(event.getCamera().getPosition()) + range);
        fog.getUniformSafe("MaxDistance").setFloat((float) maxDistance);
        try {
            VeilRenderSystem.drawScreenQuad();
        } finally {
            fog.removeTexture("DepthSampler");
            fog.removeTexture("OpaqueSampler");
            HeadlightOcclusion.unbindLookup(fog);
        }
        boolean diag = HeadlightDiagnostics.due();
        if (diag) {
            HeadlightDiagnostics.logFog(beams.size(), value(TuningOption.SABLE_HEADLIGHT_FOG_DENSITY),
                    (int) value(TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES), TARGETS.fogWidth(), TARGETS.fogHeight(),
                    TARGETS.fogTexture());
        }

        TARGETS.bindOutput();
        additiveBlend();
        // Same beam uniforms as the march: the composite also draws the full-resolution lens glare.
        bindBeamUniforms(composite, event, beams, range);
        composite.setTexture("FogSampler", GL11.GL_TEXTURE_2D, TARGETS.fogTexture());
        composite.setTexture("DepthSampler", GL11.GL_TEXTURE_2D, main.getDepthTextureId());
        composite.bindSamplers(0);
        float[] before = diag ? HeadlightDiagnostics.readCenter(main.width, main.height) : null;
        try {
            VeilRenderSystem.drawScreenQuad();
            if (diag) HeadlightDiagnostics.logComposite(before, HeadlightDiagnostics.readCenter(main.width, main.height));
        } finally {
            composite.removeTexture("FogSampler");
            composite.removeTexture("DepthSampler");
        }
    }

    private HeadlightRenderer() {}
}
