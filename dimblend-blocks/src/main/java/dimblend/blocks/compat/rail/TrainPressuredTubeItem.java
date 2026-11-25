package dimblend.blocks.compat.rail;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 列车加压管道的物品形态：放置同样受「仅能放在 Sable 子层级」约束（双端一致，零幽灵方块），
 * 悬浮提示两行灰字说明进液方式与跨车厢串联方式。
 */
public class TrainPressuredTubeItem extends BlockItem {

    public TrainPressuredTubeItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public String getDescriptionId() {
        return "block.dimblend_blocks.train_pressured_tube";
    }

    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        return TrainTubeSable.canPlace(context.getLevel(), context.getClickedPos(), context.getPlayer())
                && super.canPlace(context, state);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.dimblend_blocks.train_pressured_tube.tooltip.1")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.dimblend_blocks.train_pressured_tube.tooltip.2")
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
