package dimblend.radio.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.Command;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dimblend.radio.DimBlendRadio;
import dimblend.radio.SubLevelProjection;
import dimblend.radio.acoustics.bake.PathingBake;
import dimblend.radio.client.AcousticBakeScheduler.Inspection;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * {@code /radioacoustics bakeview [on|off]}: shows the playing radios' baked pathing in the world.
 * Each probe is a small box coloured by its radio's {@link AcousticBakeScheduler.BakeState}; probes
 * in or next to a section whose blocks changed since the bake turn red, and those sections are
 * outlined. Probes behind terrain show faintly through it. A label above each radio and a panel
 * (or the debug screen, when open) give the bake's state, what a due bake waits for, the idle
 * gate's reasoning and the path the radio's session renders now. A structure's radio is drawn with
 * its structure, in whose frame it is baked.
 */
@EventBusSubscriber(modid = DimBlendRadio.MODID, value = Dist.CLIENT)
public final class AcousticBakeView {
    /** Half the edge of a probe marker, in blocks. */
    private static final double PROBE_HALF = 0.2;
    /** Opacity of what lies behind terrain. */
    private static final float HIDDEN_ALPHA = 0.3f;
    private static final float LABEL_SCALE = 0.03f;

    /**
     * Lines drawn through terrain: the vanilla line type without the depth test. A holder, so the
     * render type is built on first draw rather than when mod loading registers this subscriber.
     */
    private static final class SeeThroughLines {
        static final RenderType TYPE = RenderType.create("dimblend_radio_bake_view_lines",
                DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }

    /** Which probes are near a change, for the bake and changed sections it was worked out from. */
    private record NearChanges(PathingBake bake, long[] changedSections, BitSet probes) { }

    private static boolean open;
    private static final Map<BlockPos, NearChanges> NEAR = new HashMap<>();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("radioacoustics")
                .then(Commands.literal("bakeview")
                        .executes(context -> show(context.getSource(), !open))
                        .then(Commands.literal("on").executes(context -> show(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> show(context.getSource(), false)))));
    }

    private static int show(CommandSourceStack source, boolean next) {
        open = next;
        AcousticBakeScheduler.setInspecting(next);
        NEAR.clear();
        source.sendSuccess(() -> Component.literal(next
                ? "Radio bake view on: probes and changed sections around playing radios, details on screen"
                : "Radio bake view off"), false);
        return Command.SINGLE_SUCCESS;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!open || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = event.getCamera().getPosition();
        List<Inspection> radios = AcousticBakeScheduler.inspect(camera, System.nanoTime());
        if (radios.isEmpty()) return;
        float partialTick = event.getCamera().getPartialTickTime();
        // At this stage the pose is the identity and the camera's rotation is in the model-view
        // matrix: geometry goes in relative to the camera position.
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // One render type at a time: the shared buffer flushes when the type changes.
        drawLines(pose, buffers.getBuffer(SeeThroughLines.TYPE), radios, camera, partialTick, HIDDEN_ALPHA);
        buffers.endBatch(SeeThroughLines.TYPE);
        drawLines(pose, buffers.getBuffer(RenderType.lines()), radios, camera, partialTick, 1);
        buffers.endBatch(RenderType.lines());
        var gate = AcousticBakeScheduler.gateStatus();
        boolean baking = AcousticBakeScheduler.baking();
        for (Inspection radio : radios) {
            Vec3 label = toWorld(radio, partialTick, Vec3.atCenterOf(radio.radio())).add(0, 1.4, 0);
            int colour = 0xFF000000 | AcousticBakeReadout.colour(radio.state());
            DebugRenderer.renderFloatingText(pose, buffers, AcousticBakeReadout.headline(radio).replaceAll("§.", ""),
                    label.x, label.y + 0.3, label.z, colour, LABEL_SCALE, true, 0, true);
            DebugRenderer.renderFloatingText(pose, buffers, AcousticBakeReadout.detail(radio, gate, baking).replaceAll("§.", ""),
                    label.x, label.y, label.z, 0xFFFFFFFF, LABEL_SCALE, true, 0, true);
        }
        buffers.endBatch();
    }

    private static void drawLines(PoseStack pose, VertexConsumer lines, List<Inspection> radios, Vec3 camera, float partialTick,
            float alpha) {
        for (Inspection radio : radios) {
            Pose3dc frame = frame(radio, partialTick);
            int colour = AcousticBakeReadout.colour(radio.state());
            box(pose, lines, toWorld(frame, new AABB(radio.radio()).inflate(0.05)), camera, colour, alpha);
            for (long section : radio.changedSections()) {
                AABB box = new AABB(SectionPos.sectionToBlockCoord(SectionPos.x(section)),
                        SectionPos.sectionToBlockCoord(SectionPos.y(section)), SectionPos.sectionToBlockCoord(SectionPos.z(section)),
                        SectionPos.sectionToBlockCoord(SectionPos.x(section) + 1), SectionPos.sectionToBlockCoord(SectionPos.y(section) + 1),
                        SectionPos.sectionToBlockCoord(SectionPos.z(section) + 1));
                box(pose, lines, toWorld(frame, box), camera, AcousticBakeReadout.CHANGED, alpha);
            }
            PathingBake bake = radio.bake();
            if (bake == null) continue;
            BitSet near = nearChanges(radio);
            for (int i = 0; i < bake.probeCount(); i++) {
                Vec3 probe = toWorld(frame, bake.probe(i));
                box(pose, lines, new AABB(probe, probe).inflate(PROBE_HALF), camera,
                        near.get(i) ? AcousticBakeReadout.CHANGED : colour, alpha);
            }
        }
    }

    /** Where the radio's structure is drawn this frame, or null for a radio on the ground. */
    private static Pose3dc frame(Inspection radio, float partialTick) {
        return radio.structure() == null ? null : SubLevelProjection.framePose(radio.structure(), partialTick);
    }

    private static Vec3 toWorld(Inspection radio, float partialTick, Vec3 position) {
        return toWorld(frame(radio, partialTick), position);
    }

    private static Vec3 toWorld(Pose3dc frame, Vec3 position) {
        return frame == null ? position : frame.transformPosition(position);
    }

    /** The world box around {@code box} as its structure is drawn. */
    private static AABB toWorld(Pose3dc frame, AABB box) {
        return frame == null ? box : new BoundingBox3d(box).transform(frame).toMojang();
    }

    private static void box(PoseStack pose, VertexConsumer lines, AABB box, Vec3 camera, int rgb, float alpha) {
        LevelRenderer.renderLineBox(pose, lines, box.move(camera.reverse()),
                (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
    }

    /** Probes within their influence radius of a changed section; recomputed when either changes. */
    private static BitSet nearChanges(Inspection radio) {
        NearChanges cached = NEAR.get(radio.radio());
        if (cached == null || cached.bake() != radio.bake() || cached.changedSections() != radio.changedSections()) {
            cached = new NearChanges(radio.bake(), radio.changedSections(),
                    radio.bake().probesNear(radio.changedSections(), radio.bake().cellSize()));
            NEAR.put(radio.radio(), cached);
        }
        return cached.probes();
    }

    /** With the debug screen open, the readout joins its left column. */
    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!open) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        event.getLeft().add("");
        event.getLeft().addAll(readout(mc));
    }

    /** Otherwise it has a panel of its own, in the debug screen's style. */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!open || mc.level == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        var graphics = event.getGuiGraphics();
        int y = 2;
        for (String line : readout(mc)) {
            int width = mc.font.width(line);
            graphics.fill(1, y - 1, 2 + width + 1, y + mc.font.lineHeight - 1, 0x90505050);
            graphics.drawString(mc.font, line, 2, y, 0xE0E0E0, false);
            y += mc.font.lineHeight;
        }
    }

    private static List<String> readout(Minecraft mc) {
        Vec3 listener = mc.gameRenderer.getMainCamera().getPosition();
        return AcousticBakeReadout.lines(AcousticBakeScheduler.gateStatus(), AcousticBakeScheduler.baking(),
                AcousticBakeScheduler.inspect(listener, System.nanoTime()), listener, RadioAcousticController::pathingField);
    }

    private AcousticBakeView() { }
}
