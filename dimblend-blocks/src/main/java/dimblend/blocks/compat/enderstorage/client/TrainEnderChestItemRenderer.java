package dimblend.blocks.compat.enderstorage.client;

import com.mojang.blaze3d.vertex.PoseStack;

import codechicken.enderstorage.api.Frequency;
import codechicken.lib.model.PerspectiveModelState;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.item.IItemRenderer;
import codechicken.lib.util.TransformUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 列车末影物流箱物品 3D 渲染器：由 {@code models/item/train_ender_chest.json}
 * 经 {@code codechickenlib:class} 加载器反射无参构造，实时展示物品组件上的三色羊毛标记与钻石锁。
 */
public class TrainEnderChestItemRenderer implements IItemRenderer {

    private final TrainEnderChestRenderer tileRender = new TrainEnderChestRenderer(null);

    public TrainEnderChestItemRenderer() {
    }

    @Override
    public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
            MultiBufferSource source, int packedLight, int packedOverlay) {
        CCRenderState ccrs = CCRenderState.instance();
        ccrs.reset();
        Frequency freq = Frequency.readFromStack(stack);
        ccrs.brightness = packedLight;
        ccrs.overlay = packedOverlay;
        this.tileRender.renderChest(ccrs, poseStack, source, 2, freq, 0.0f, 0);
    }

    @Override
    public PerspectiveModelState getModelState() {
        return TransformUtils.DEFAULT_BLOCK;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return false;
    }
}
