package dimblend.blocks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.blocks.api.CreativeCopycatHidable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.Tags;

/**
 * 隐藏伪装方块的「持扳手暂时显现」渲染：本地玩家主/副手持有扳手
 * （{@code c:tools/wrench}）时，把方块画成其物品形态（伪装基底外观）。
 *
 * <p>隐藏以「未贴材质」为前提，故基底外观就是该方块的正常外观；直接画物品模型，
 * 不依赖世界模型的内部数据流（Create 板与 Copycats+ 半砖的材质管线各异）。
 * 代价：幽灵不反映朝向/上下半等形态差异——定位与恢复不受影响（碰撞箱仍在）。</p>
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
        BlockState state = blockEntity.getBlockState();
        ItemStack itemForm = new ItemStack(state.getBlock());
        BakedModel model = minecraft.getItemRenderer().getModel(itemForm, minecraft.level, null, 0);
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        poseStack.pushPose();
        dispatcher.getModelRenderer().renderModel(poseStack.last(),
                bufferSource.getBuffer(Sheets.solidBlockSheet()), state, model, 1.0F, 1.0F, 1.0F,
                packedLight, packedOverlay, ModelData.EMPTY, Sheets.solidBlockSheet());
        poseStack.popPose();
    }
}
