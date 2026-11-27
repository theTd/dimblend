package dimblend.experience.compat.cca.client;

import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mrh0.createaddition.config.CommonConfig;
import com.mrh0.createaddition.energy.IWireNode;
import com.mrh0.createaddition.energy.WireType;
import com.mrh0.createaddition.rendering.CARenderType;
import com.mrh0.createaddition.rendering.WireNodeRenderer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dimblend.experience.compat.cca.CrossSableWireGeometry;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;

/**
 * 跨 Sable 子层级接线器线缆的<b>加粗渲染</b>（客户端）：把 CCA 原版的扁平 ribbon 换成
 * 2 像素见方的四边封闭棱柱，材质 / 颜色 / 光照 / 弯曲线完全沿用 CCA 自己的实现。
 *
 * <p>判定：两端 {@code BlockPos} 各自用 {@code SableCompanion.INSTANCE.getContainingClient}
 * 解析出所属子层级 UUID（{@code dimblend-dev} §8.67 口径：走 {@code SableCompanion.INSTANCE}
 * 接口 API，而不是 {@code Sable.HELPER} 的字段类型）；只有"两个非空且不同的 UUID"
 * （{@link CrossSableWireGeometry#isCrossSubLevel}）才接管渲染。真实世界↔载具、
 * 同一载具内部、手持线缆连向真实世界玩家都返回 {@code false}，交给 CCA 原版细线。</p>
 *
 * <p>为什么只改跨层级：用户 2026-11-27 的口径是"跨 sable 链接的接线器之间的线缆"；
 * 同一载具/真实世界里的普通细线保持原样。</p>
 */
public final class CrossSableWireRenderer {

    /**
     * 尝试用 2px × 2px 方柱渲染这条线。
     *
     * @return {@code true} 表示已接管渲染（调用方应 cancel 原版 {@code wireRender}）；
     *         {@code false} 表示不是跨子层级连线，交给 CCA 原版
     */
    public static boolean tryRenderCrossSable(BlockEntity tileEntityIn, BlockPos other,
                                              PoseStack stack, MultiBufferSource buffer,
                                              float x, float y, float z, WireType type, float dis) {
        if (tileEntityIn == null || other == null || tileEntityIn.getLevel() == null || type == null) {
            return false;
        }
        if (!isConnectedNode(tileEntityIn, other)) {
            return false;
        }
        if (!CrossSableWireGeometry.isCrossSubLevel(
                subLevelId(tileEntityIn), subLevelId(other))) {
            return false;
        }

        Level level = tileEntityIn.getLevel();
        BlockPos firstPos = tileEntityIn.getBlockPos();
        VertexConsumer vc = buffer.getBuffer(CARenderType.WIRE);
        Matrix4f matrix = stack.last().pose();

        int blockLightFirst = level.getBrightness(LightLayer.BLOCK, firstPos);
        int blockLightSecond = level.getBrightness(LightLayer.BLOCK, other);
        int skyLightFirst = level.getBrightness(LightLayer.SKY, firstPos);
        int skyLightSecond = level.getBrightness(LightLayer.SKY, other);

        float maxLength = CommonConfig.SMALL_CONNECTOR_MAX_LENGTH.get();
        float[] mesh = CrossSableWireGeometry.mesh(x, y, z, dis, maxLength);
        int segments = CrossSableWireGeometry.SEGMENTS;
        int ringFloats = CrossSableWireGeometry.FLOATS_PER_RING;

        for (int j = 0; j < segments; j++) {
            // 与 CCA wirePart 完全一致：f = j / 23，光从 blockpos2（other）向 blockpos1 插值。
            float f = (float) j / (float) (segments - 1);
            int blockLight = (int) Mth.lerp(f, (float) blockLightSecond, (float) blockLightFirst);
            int skyLight = (int) Mth.lerp(f, (float) skyLightSecond, (float) skyLightFirst);
            int light = LightTexture.pack(blockLight, skyLight);

            int red = type.getRed();
            int green = type.getGreen();
            int blue = type.getBlue();
            if (j % 2 == 0) {
                red = (int) ((float) red * 0.7F);
                green = (int) ((float) green * 0.7F);
                blue = (int) ((float) blue * 0.7F);
            }

            int ringFrom = j * ringFloats;
            int ringTo = (j + 1) * ringFloats;
            for (int corner = 0; corner < 4; corner++) {
                int next = (corner + 1) % 4;
                addVertex(vc, matrix, mesh, ringFrom + corner * 3, red, green, blue, light);
                addVertex(vc, matrix, mesh, ringFrom + next * 3, red, green, blue, light);
                addVertex(vc, matrix, mesh, ringTo + next * 3, red, green, blue, light);
                addVertex(vc, matrix, mesh, ringTo + corner * 3, red, green, blue, light);
            }
        }

        if (type.isFestive()) {
            // 与 CCA wirePart 的 festive 分支一致：每段在曲线上摆一颗小灯。
            stack.pushPose();
            boolean main = x + y + z > 0.0F;
            for (int j = 0; j < segments; j++) {
                WireNodeRenderer.lights(vc, x, y, z, 0.025F, 0.025F, segments, j + 1,
                        0.0F, 0.0F, type, dis, tileEntityIn.getBlockState(), stack, 0, main);
            }
            stack.popPose();
        }
        return true;
    }

    /** 仅对已连接的接线器对端生效，排除手持线轴拖向玩家脚下坐标的预览线。 */
    private static boolean isConnectedNode(BlockEntity tileEntityIn, BlockPos other) {
        if (!(tileEntityIn instanceof IWireNode wireNode)) {
            return false;
        }
        int count = wireNode.getNodeCount();
        for (int i = 0; i < count; i++) {
            if (wireNode.hasConnection(i) && other.equals(wireNode.getNodePos(i))) {
                return true;
            }
        }
        return false;
    }

    /** 方块实体所在子层级 UUID；不在任何载具内返回 {@code null}。 */
    @Nullable
    private static UUID subLevelId(BlockEntity blockEntity) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContainingClient(blockEntity);
        return subLevel == null ? null : subLevel.getUniqueId();
    }

    @Nullable
    private static UUID subLevelId(BlockPos pos) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContainingClient(pos);
        return subLevel == null ? null : subLevel.getUniqueId();
    }

    private static void addVertex(VertexConsumer vc, Matrix4f matrix, float[] mesh, int base,
                                  int red, int green, int blue, int light) {
        vc.addVertex(matrix, mesh[base], mesh[base + 1], mesh[base + 2])
                .setColor(red, green, blue, 255)
                .setLight(light);
    }

    private CrossSableWireRenderer() {
    }
}
