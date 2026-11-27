package dimblend.experience.compat.connected.mobdisplay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * 生物展示催化剂的渲染：把抓捕盒里那只生物画在方块正中，缓慢绕 Y 轴自转
 * （Audit Default #12，2026-11-25 口径）。
 *
 * <ul>
 * <li><b>方块中心要先投出 Sable</b>：方块实体活在子层级坐标系里，相机距离与视锥都按子层级算，
 * 所以用 {@code ClientSubLevel#renderPose()} 把中心投到真实世界坐标，再做 16 格距离判定；
 * 不投出去的话，载具开走时生物会留在原地。</li>
 * <li><b>统一缩放</b>：{@code scale = min(0.4 / max(bbW, bbH, 0.001), 0.4)}，
 * 让最大的那只生物也塞进 0.4 格；中心对齐 {@code (0.5, 0.5 - bbH*scale*0.5, 0.5)}。
 * 缩放与自转公式都收在 {@link MobDisplayCatalystRules} 里（纯函数，可单测）。</li>
 * <li><b>Y 轴转速</b> {@code (System.currentTimeMillis() / 25.0) % 360}（与 {@code @Local} 无关，
 * 纯墙钟：暂停游戏也继续转，和 Create 的展示件同手感），即 {@code 40°/s}、<b>9 秒一圈</b>——
 * 这是唯一的"转"，速度刻意压得很温和。</li>
 * <li><b>生物自身朝向必须每帧锁死</b>：{@link MobDisplayCatalystBlockEntity#lockDisplayPose} 把
 * {@code yRot/yRotO/yBodyRot/yBodyRotO/yHeadRot/yHeadRotO} 全部归零后再进 {@code EntityRenderDispatcher}，
 * 否则 {@code LivingEntityRenderer#render} 的 {@code Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)}
 * 会让生物每 tick 在 {@code 0°} 与抓捕朝向之间狂甩（2026-11-27 用户报告的"高速旋转/剧烈左右摆动"）。
 * {@code partialTick} 仍照常传下去：{@code getBob}（待机呼吸/翅膀微动）靠它平滑推进。</li>
 * <li><b>不要影子</b>：实体渲染器的阴影是贴地椭圆，在方块内部会穿帮；渲染前后
 * {@code finally} 复位，绝不污染同帧别的实体。</li>
 * </ul>
 */
public final class MobDisplayCatalystRenderer
        implements BlockEntityRenderer<MobDisplayCatalystBlockEntity> {

    /** 只在这个世界距离内画（格，平方比较）。 */
    private static final double RENDER_DISTANCE_SQR = 16.0D * 16.0D;

    public MobDisplayCatalystRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MobDisplayCatalystBlockEntity catalyst, float partialTick, PoseStack pose,
            MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Level level = catalyst.getLevel();
        Entity display = catalyst.displayEntity();
        if (level == null || display == null) {
            return;
        }
        int x = catalyst.getBlockPos().getX();
        int y = catalyst.getBlockPos().getY();
        int z = catalyst.getBlockPos().getZ();
        Vector3d center = new Vector3d(x + 0.5D, y + 0.5D, z + 0.5D);
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
            SableProjection.projectOut(catalyst, center);
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (camera.distanceToSqr(center.x, center.y, center.z) > RENDER_DISTANCE_SQR) {
            return;
        }
        float scale = MobDisplayCatalystRules.displayScale(display.getBbWidth(), display.getBbHeight());
        float rotation = MobDisplayCatalystRules.rotationDegrees(System.currentTimeMillis());

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        // 进渲染前的最后一道兜底：把生物自身朝向与插值历史全部归零（详见 lockDisplayPose 的注释）。
        MobDisplayCatalystBlockEntity.lockDisplayPose(display);
        pose.pushPose();
        // PoseStack 在进入 BlockEntityRenderer 时已定位到本方块原点（Sable 子层级亦已施加 renderPose），
        // 只需移到方块正中 (0.5, 0.5, 0.5)；上面的 center 投影仅用于 16 格世界距离裁剪。
        pose.translate(0.5D, 0.5D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(rotation));
        pose.scale(scale, scale, scale);
        // 实体原点是脚底：下移半个身高，让"体心"落在方块中心
        pose.translate(0.0D, -display.getBbHeight() * 0.5D, 0.0D);
        dispatcher.setRenderShadow(false);
        try {
            dispatcher.render(display, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, pose, buffer, packedLight);
        } finally {
            dispatcher.setRenderShadow(true);
        }
        pose.popPose();
    }

    /** 生物可以超出方块一格（大型生物缩放后仍可能越过边界），渲染包围盒放宽一格。 */
    @Override
    public AABB getRenderBoundingBox(MobDisplayCatalystBlockEntity catalyst) {
        return new AABB(catalyst.getBlockPos()).inflate(1.0D);
    }

    /** 不做相机距离裁剪：真正的位置判定已经在 {@link #render} 里按投影后的世界坐标做了。 */
    @Override
    public boolean shouldRender(MobDisplayCatalystBlockEntity catalyst, Vec3 cameraPos) {
        return true;
    }

    /**
     * Sable 投影单独放一个嵌套类：{@code sable} 是 compileOnly，缺席时本类可以被加载，
     * 但绝不能在内层方法里直接引用 {@code dev.ryanhcode.sable.*}（否则验证/解析即
     * NoClassDefFoundError）。只有 {@code ModList.isLoaded("sable")} 通过后才第一次触碰本类。
     */
    private static final class SableProjection {
        static void projectOut(MobDisplayCatalystBlockEntity catalyst, Vector3d center) {
            ClientSubLevel subLevel = Sable.HELPER.getContainingClient(catalyst);
            if (subLevel != null) {
                subLevel.renderPose().transformPosition(center);
            }
        }

        private SableProjection() {
        }
    }
}
