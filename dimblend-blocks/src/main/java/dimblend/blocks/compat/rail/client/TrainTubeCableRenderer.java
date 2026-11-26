package dimblend.blocks.compat.rail.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.rail.TrainTubeCableGeometry;
import dimblend.blocks.compat.rail.TrainTubeLink;
import dimblend.blocks.compat.rail.TrainTubeSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 跨车厢管道线缆的世界渲染：每帧按 {@link ClientTrainTubeLinks} 的连接表，
 * 把两个 Sable 子层级上的端点用一根带下垂的 2×2 px 方截面线缆连起来，并在两端各画一个小线框。
 *
 * <p>四条口径，都是本轮踩出来的：</p>
 * <ol>
 *   <li><b>阶段选 {@code AFTER_PARTICLES}，pose 当 identity 用</b>：{@code RenderType} 绑定的那些阶段
 *       （{@code AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS} 等）由 {@code ClientHooks#dispatchRenderStage(RenderType, ...)}
 *       派发时 {@code poseStack} 传的是 {@code null}，事件里是 {@code new PoseStack()}——本来就是 identity。
 *       所以几何必须自己减相机，和 {@code AcousticBakeView} 的既有写法一致。</li>
 *   <li><b>partialTick 取相机那一帧</b>（{@code Camera#getPartialTickTime}）：{@code GameRenderer#renderLevel}
 *       用 {@code getGameTimeDeltaPartialTick(true)} 建相机（实体冻结时换成 {@code 1.0F}），Sable 的
 *       {@code ClientSubLevel#renderPose()} 无参版读的也是同一个值——两边必须同一个数，线缆才贴住车体。</li>
 *   <li><b>端点从方块中心推到管口表面</b>（{@link TrainTubeCableGeometry#endpoint}）：方块中心埋在车厢壳体里，
 *       照它起笔线缆整根看不见；偏移后从管口长出来。</li>
 *   <li><b>世界 → 相机差值在 double 上做</b>（{@link TrainTubeCableGeometry#cameraRelative}）：PoseStack 矩阵是 float，
 *       而 plot 坐标原点是 20,480,000（{@code dimblend-dev} §8.47）。</li>
 * </ol>
 */
public final class TrainTubeCableRenderer {

    private static final ResourceLocation CABLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "textures/block/train_pressured_tube_cable.png");
    private static final RenderType CABLE_TYPE = RenderType.entityCutoutNoCull(CABLE_TEXTURE);

    /** 已连接端点的标记色（青绿）。 */
    private static final int LINKED_MARKER_COLOUR = 0x4FD6C6;
    /** 挂起选择的标记色（琥珀）。 */
    private static final int SELECTION_MARKER_COLOUR = 0xFFC24A;

    /** 端点标记线框半边长。 */
    private static final double LINK_MARKER_HALF = 0.18;
    private static final double SELECTION_MARKER_HALF = 0.30;

    /** 一根本帧要画的线缆（两端世界坐标 + 打包亮度）。 */
    private record VisualCable(Vec3 from, Vec3 to, int light) {
    }

    private TrainTubeCableRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();
        List<TrainTubeLink> links = ClientTrainTubeLinks.links(dimension);
        TrainTubeSelection selection = ClientTrainTubeLinks.selection(dimension);
        if (links.isEmpty() && selection == null) {
            return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        float partialTick = event.getCamera().getPartialTickTime();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        List<VisualCable> drawn = new ArrayList<>();
        for (TrainTubeLink link : links) {
            VisualCable cable = project(level, container, link, partialTick, event);
            if (cable != null) {
                drawn.add(cable);
            }
        }

        if (!drawn.isEmpty()) {
            VertexConsumer quads = buffers.getBuffer(CABLE_TYPE);
            PoseStack.Pose quadPose = pose.last();
            for (VisualCable cable : drawn) {
                renderCable(quadPose, quads, cable, camera);
            }
            buffers.endBatch(CABLE_TYPE);
        }

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (VisualCable cable : drawn) {
            marker(pose, lines, cable.from(), camera, LINK_MARKER_HALF, LINKED_MARKER_COLOUR, 0.85f);
            marker(pose, lines, cable.to(), camera, LINK_MARKER_HALF, LINKED_MARKER_COLOUR, 0.85f);
        }
        if (selection != null) {
            ClientSubLevel subLevel = clientSubLevel(container, selection.subLevel());
            if (subLevel != null) {
                Vec3 center = subLevel.renderPose(partialTick).transformPosition(Vec3.atCenterOf(selection.pos()));
                marker(pose, lines, center, camera, SELECTION_MARKER_HALF, SELECTION_MARKER_COLOUR, pulse(level, partialTick));
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    /** 把一条连接的端点投影成这一帧的世界坐标；不可画时返回 {@code null}。 */
    private static VisualCable project(ClientLevel level, ClientSubLevelContainer container, TrainTubeLink link,
            float partialTick, RenderLevelStageEvent event) {
        ClientSubLevel first = clientSubLevel(container, link.subA());
        ClientSubLevel second = clientSubLevel(container, link.subB());
        if (first == null || second == null) {
            return null;
        }
        Vec3 centerA = Vec3.atCenterOf(link.posA());
        Vec3 centerB = Vec3.atCenterOf(link.posB());
        Pose3dc poseA = first.renderPose(partialTick);
        Pose3dc poseB = second.renderPose(partialTick);
        Vec3 worldA = poseA.transformPosition(centerA);
        Vec3 worldB = poseB.transformPosition(centerB);
        // 各自把对端搬进自己的 plot 坐标系，再沿"朝对端"的主轴把端点推到管口表面
        Vec3 from = poseA.transformPosition(
                TrainTubeCableGeometry.endpoint(centerA, poseA.transformPositionInverse(worldB)));
        Vec3 to = poseB.transformPosition(
                TrainTubeCableGeometry.endpoint(centerB, poseB.transformPositionInverse(worldA)));
        if (!TrainTubeCableGeometry.isDrawable(from.distanceTo(to))) {
            return null;
        }
        AABB bounds = new AABB(from, to).inflate(0.5);
        if (!event.getFrustum().isVisible(bounds)) {
            return null;
        }
        int light = TrainTubeCableGeometry.brightestLight(
                LevelRenderer.getLightColor(level, BlockPos.containing(from)),
                LevelRenderer.getLightColor(level, BlockPos.containing(to)),
                LevelRenderer.getLightColor(level, BlockPos.containing(from.lerp(to, 0.5))));
        return new VisualCable(from, to, light);
    }

    /** 端点线框：小方盒画在世界坐标（减相机后交给 identity 姿态）。 */
    private static void marker(PoseStack pose, VertexConsumer lines, Vec3 center, Vec3 camera, double half,
            int rgb, float alpha) {
        AABB box = new AABB(center, center).inflate(half).move(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(pose, lines, box,
                (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
    }

    private static float pulse(ClientLevel level, float partialTick) {
        float phase = (float) (level.getGameTime() % 20L) * 0.35f + partialTick * 0.35f;
        return 0.55f + 0.35f * (Mth.sin(phase) * 0.5f + 0.5f);
    }

    private static ClientSubLevel clientSubLevel(ClientSubLevelContainer container, UUID id) {
        return container.getSubLevel(id) instanceof ClientSubLevel client ? client : null;
    }

    // ------------------------------------------------------------------ 线缆几何

    private static void renderCable(PoseStack.Pose pose, VertexConsumer consumer, VisualCable cable, Vec3 camera) {
        Vec3[] points = TrainTubeCableGeometry.cablePoints(cable.from(), cable.to(),
                TrainTubeCableGeometry.SEGMENTS);
        for (int index = 0; index + 1 < points.length; index++) {
            renderSegment(pose, consumer, points[index], points[index + 1], cable.light(), camera);
        }
    }

    private static void renderSegment(PoseStack.Pose pose, VertexConsumer consumer, Vec3 from, Vec3 to,
            int light, Vec3 camera) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 direction = axis.normalize();
        Vec3 reference = Math.abs(direction.y) > 0.9 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 side = direction.cross(reference).normalize().scale(TrainTubeCableGeometry.HALF_WIDTH);
        Vec3 up = side.cross(direction).normalize().scale(TrainTubeCableGeometry.HALF_WIDTH);

        Vec3 a = side.add(up);
        Vec3 b = side.subtract(up);
        Vec3 c = side.scale(-1.0).subtract(up);
        Vec3 d = side.scale(-1.0).add(up);

        quad(pose, consumer, from.add(a), from.add(b), to.add(b), to.add(a), light, camera);
        quad(pose, consumer, from.add(b), from.add(c), to.add(c), to.add(b), light, camera);
        quad(pose, consumer, from.add(c), from.add(d), to.add(d), to.add(c), light, camera);
        quad(pose, consumer, from.add(d), from.add(a), to.add(a), to.add(d), light, camera);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer, Vec3 first, Vec3 second, Vec3 third,
            Vec3 fourth, int light, Vec3 camera) {
        Vec3 normal = second.subtract(first).cross(third.subtract(first));
        normal = normal.lengthSqr() < 1.0E-9 ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
        vertex(pose, consumer, first, 0.0f, 0.0f, normal, light, camera);
        vertex(pose, consumer, second, 1.0f, 0.0f, normal, light, camera);
        vertex(pose, consumer, third, 1.0f, 1.0f, normal, light, camera);
        vertex(pose, consumer, fourth, 0.0f, 1.0f, normal, light, camera);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, Vec3 position, float u, float v,
            Vec3 normal, int light, Vec3 camera) {
        Vec3 relative = TrainTubeCableGeometry.cameraRelative(position, camera);
        consumer.addVertex(pose, (float) relative.x, (float) relative.y, (float) relative.z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
