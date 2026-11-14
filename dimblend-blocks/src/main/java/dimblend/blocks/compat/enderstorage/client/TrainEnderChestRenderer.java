package dimblend.blocks.compat.enderstorage.client;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.client.model.ButtonModelLibrary;
import codechicken.enderstorage.client.render.RenderCustomEndPortal;
import codechicken.enderstorage.client.render.tile.RenderTileEnderChest;
import codechicken.lib.colour.EnumColour;
import codechicken.lib.render.CCModelLibrary;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.RenderUtils;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.util.ClientUtils;
import codechicken.lib.vec.Matrix4;
import codechicken.lib.vec.Rotation;
import codechicken.lib.vec.Vector3;
import codechicken.lib.vec.uv.UVTranslation;
import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.enderstorage.BlockTrainEnderChest;
import dimblend.blocks.compat.enderstorage.TrainEnderChestBlockEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * 列车末影物流箱方块实体渲染器：几何、开盖旋转、三色羊毛垫片、私有钻石锁、内部末地传送门深渊
 * 与悬浮珍珠均与 {@link RenderTileEnderChest} 一致，箱体贴图替换为物品保险库风格的
 * {@code dimblend_blocks:textures/entity/train_ender_chest.png}。
 */
public class TrainEnderChestRenderer implements BlockEntityRenderer<TrainEnderChestBlockEntity> {

    private static final RenderType CHEST_TYPE = RenderType.entityCutout(
            ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "textures/entity/train_ender_chest.png"));
    private static final RenderType BUTTON_TYPE = RenderType.entitySolid(
            ResourceLocation.fromNamespaceAndPath("enderstorage", "textures/buttons.png"));
    private static final RenderType PEARL_TYPE = CCModelLibrary.getIcos4RenderType(
            ResourceLocation.fromNamespaceAndPath("enderstorage", "textures/hedronmap.png"));
    private static final RenderCustomEndPortal RENDER_END_PORTAL =
            new RenderCustomEndPortal(0.626, 0.188, 0.812, 0.188, 0.812);

    private final ModelPart bottom;
    private final ModelPart lid;
    private final ModelPart lock;
    private final ModelPart diamondLock;

    public TrainEnderChestRenderer(@Nullable BlockEntityRendererProvider.Context context) {
        ModelPart chestRoot = RenderTileEnderChest.createChestLayer().bakeRoot();
        this.bottom = chestRoot.getChild("bottom");
        this.lid = chestRoot.getChild("lid");
        this.lock = chestRoot.getChild("lock");
        this.diamondLock = chestRoot.getChild("diamond_lock");
    }

    @Override
    public void render(TrainEnderChestBlockEntity chest, float partialTicks, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        CCRenderState ccrs = CCRenderState.instance();
        ccrs.brightness = packedLight;
        ccrs.overlay = packedOverlay;
        this.renderChest(ccrs, poseStack, bufferSource, chest.rotation, chest.getFrequency(),
                (float) chest.getRadianLidAngle(partialTicks),
                RenderUtils.getTimeOffset(chest.getBlockPos()));
    }

    public void renderChest(CCRenderState ccrs, PoseStack pose, MultiBufferSource source,
            int rotation, Frequency freq, float lidAngle, int pearlOffset) {
        Matrix4 mat = new Matrix4(pose);
        if (lidAngle != 0.0f) {
            RENDER_END_PORTAL.render(mat, source);
        }
        ccrs.reset();
        pose.pushPose();
        pose.translate(0.0, 1.0, 1.0);
        pose.scale(1.0f, -1.0f, -1.0f);
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(new Quaternionf().rotateXYZ(0.0f, (float) ((rotation * 90.0f) * 0.017453292519943), 0.0f));
        pose.translate(-0.5, -0.5, -0.5);
        VertexConsumer chestCons = source.getBuffer(CHEST_TYPE);
        this.lid.xRot = lidAngle;
        this.lock.xRot = lidAngle;
        this.diamondLock.xRot = lidAngle;
        this.lid.render(pose, chestCons, ccrs.brightness, ccrs.overlay);
        this.bottom.render(pose, chestCons, ccrs.brightness, ccrs.overlay);
        if (freq.hasOwner()) {
            this.diamondLock.render(pose, chestCons, ccrs.brightness, ccrs.overlay);
        } else {
            this.lock.render(pose, chestCons, ccrs.brightness, ccrs.overlay);
        }
        pose.popPose();

        mat.translate(0.5, 0.0, 0.5);
        ccrs.bind(BUTTON_TYPE, source);
        Matrix4 buttonCommon = mat.copy();
        buttonCommon.rotate((-90 * rotation) * 0.017453292519943, Vector3.Y_POS);
        buttonCommon.apply(new Rotation(lidAngle, 1.0, 0.0, 0.0).at(new Vector3(-0.5, 0.5625, -0.4375)));
        EnumColour[] colours = freq.toArray();
        for (int i = 0; i < 3; ++i) {
            Matrix4 buttonMat = buttonCommon.copy();
            buttonMat.apply(BlockTrainEnderChest.BUTTON_T[i]);
            ButtonModelLibrary.button.render(ccrs, new IVertexOperation[]{
                    buttonMat,
                    new UVTranslation(0.25 * (colours[i].getWoolMeta() % 4), 0.25 * (colours[i].getWoolMeta() / 4))
            });
        }
        if (lidAngle != 0.0f) {
            double time = ClientUtils.getRenderTime() + pearlOffset;
            Matrix4 pearlMat = RenderUtils.getMatrix(
                    mat.copy(),
                    new Vector3(0.0, 0.2 + lidAngle * -0.5 + RenderUtils.getPearlBob(time), 0.0),
                    new Rotation(time / 3.0, new Vector3(0.0, 1.0, 0.0)),
                    0.04);
            ccrs.brightness = 0xF000F0;
            ccrs.bind(PEARL_TYPE, source);
            CCModelLibrary.icosahedron4.render(ccrs, new IVertexOperation[]{pearlMat});
        }
        ccrs.reset();
    }
}
