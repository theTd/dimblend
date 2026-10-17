package dimblend.blocks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.blocks.api.CreativeCopycatHidable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.common.Tags;

/**
 * 隐藏伪装方块的「持扳手暂时显现」渲染：本地玩家主/副手持有扳手
 * （{@code c:tools/wrench}）时，按真实方块状态暂时画出伪装基底。
 *
 * <p>复用最终世界模型及其材质数据，只在本次模型数据采集时绕过隐藏过滤，保留外部装饰包装，
 * 以及板朝向、半砖上下半与轴向。
 * 不修改 BE 隐藏标记或方块状态，避免触发同步、网格重建与 Sable 物理更新。</p>
 */
public class CreativeCopycatGhostRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    public CreativeCopycatGhostRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** 玩家主/副手是否持有扳手（NeoForge 通用扳手 tag，Create 扳手在内）。 */
    public static boolean holdsWrench(Player player) {
        return player.getMainHandItem().is(Tags.Items.TOOLS_WRENCH)
                || player.getOffhandItem().is(Tags.Items.TOOLS_WRENCH);
    }

    @Override
    public void render(T blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!(blockEntity instanceof CreativeCopycatHidable hidable) || !hidable.isCopycatHidden()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !holdsWrench(minecraft.player)) {
            return;
        }
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        BlockState state = blockEntity.getBlockState();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        BakedModel model = dispatcher.getBlockModel(state);
        ModelData data = model.getModelData(level, blockEntity.getBlockPos(), state,
                HidingCopycatModel.previewData(blockEntity.getModelData()));
        RandomSource random = RandomSource.create(state.getSeed(blockEntity.getBlockPos()));
        for (RenderType layer : model.getRenderTypes(state, random, data)) {
            dispatcher.getModelRenderer().renderModel(poseStack.last(),
                    bufferSource.getBuffer(RenderTypeHelper.getEntityRenderType(layer, false)),
                    state, model, 1.0F, 1.0F, 1.0F, packedLight, packedOverlay, data, layer);
        }
    }
}
