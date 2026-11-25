package dimblend.blocks.compat.rail.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.rail.TrainTubeLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 跨车厢管道线缆的世界渲染：每帧按 {@link ClientTrainTubeLinks} 的连接表，
 * 把两个 Sable 子层级上的端点用一根带下垂的 2×2 px 方截面线缆连起来。
 *
 * <p>端点位置走 {@code ClientSubLevel#renderPose(partialTick)} 的插值姿态，
 * 所以高速行驶与转弯时线缆不会脱离管道。视觉上用 {@code entityCutoutNoCull} 的
 * 无剔除渲染（细线缆背面本来就该看得见），光照取端点处的世界亮度。</p>
 */
public final class TrainTubeCableRenderer {

    private static final ResourceLocation CABLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "textures/block/train_pressured_tube_cable.png");
    private static final RenderType CABLE_TYPE = RenderType.entityCutoutNoCull(CABLE_TEXTURE);

    /** 截面半宽：2/16 格 → 0.0625。 */
    private static final double HALF_WIDTH = 0.0625;
    private static final int SEGMENTS = 8;
    /** 超过这个距离的连接不画（正常情况下世界距离 ≤ 16，这里只是防御）。 */
    private static final double MAX_DRAW_DISTANCE = 64.0;

    private TrainTubeCableRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        List<TrainTubeLink> links = ClientTrainTubeLinks.get(level.dimension());
        if (links.isEmpty()) {
            return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer consumer = buffers.getBuffer(CABLE_TYPE);
        for (TrainTubeLink link : links) {
            SubLevel firstSubLevel = container.getSubLevel(link.subA());
            SubLevel secondSubLevel = container.getSubLevel(link.subB());
            if (!(firstSubLevel instanceof ClientSubLevel first) || !(secondSubLevel instanceof ClientSubLevel second)) {
                continue;
            }
            Vec3 from = first.renderPose(partialTick).transformPosition(Vec3.atCenterOf(link.posA()));
            Vec3 to = second.renderPose(partialTick).transformPosition(Vec3.atCenterOf(link.posB()));
            double distance = from.distanceTo(to);
            if (distance > MAX_DRAW_DISTANCE) {
                continue;
            }
            AABB bounds = new AABB(from, to).inflate(0.5);
            if (!event.getFrustum().isVisible(bounds)) {
                continue;
            }
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(from));
            renderCable(poseStack, consumer, from, to, distance, light);
        }
        poseStack.popPose();
        buffers.endBatch(CABLE_TYPE);
    }

    private static void renderCable(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to,
            double distance, int light) {
        double sag = Math.min(0.35, distance * 0.08);
        Vec3[] points = new Vec3[SEGMENTS + 1];
        for (int index = 0; index <= SEGMENTS; index++) {
            double t = (double) index / (double) SEGMENTS;
            Vec3 point = from.lerp(to, t);
            points[index] = point.add(0.0, -sag * 4.0 * t * (1.0 - t), 0.0);
        }
        PoseStack.Pose pose = poseStack.last();
        for (int index = 0; index < SEGMENTS; index++) {
            renderSegment(pose, consumer, points[index], points[index + 1], light);
        }
    }

    private static void renderSegment(PoseStack.Pose pose, VertexConsumer consumer, Vec3 from, Vec3 to, int light) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 direction = axis.normalize();
        Vec3 reference = Math.abs(direction.y) > 0.9 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 side = direction.cross(reference).normalize().scale(HALF_WIDTH);
        Vec3 up = side.cross(direction).normalize().scale(HALF_WIDTH);

        Vec3 a = side.add(up);
        Vec3 b = side.subtract(up);
        Vec3 c = side.scale(-1.0).subtract(up);
        Vec3 d = side.scale(-1.0).add(up);

        quad(pose, consumer, from.add(a), from.add(b), to.add(b), to.add(a), light);
        quad(pose, consumer, from.add(b), from.add(c), to.add(c), to.add(b), light);
        quad(pose, consumer, from.add(c), from.add(d), to.add(d), to.add(c), light);
        quad(pose, consumer, from.add(d), from.add(a), to.add(a), to.add(d), light);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer, Vec3 first, Vec3 second, Vec3 third,
            Vec3 fourth, int light) {
        Vec3 normal = second.subtract(first).cross(third.subtract(first));
        normal = normal.lengthSqr() < 1.0E-9 ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
        vertex(pose, consumer, first, 0.0f, 0.0f, normal, light);
        vertex(pose, consumer, second, 1.0f, 0.0f, normal, light);
        vertex(pose, consumer, third, 1.0f, 1.0f, normal, light);
        vertex(pose, consumer, fourth, 0.0f, 1.0f, normal, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, Vec3 position, float u, float v,
            Vec3 normal, int light) {
        consumer.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
