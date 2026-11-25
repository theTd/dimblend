package dimblend.blocks.compat.create.funnel;

import java.util.List;

import com.simibubi.create.content.logistics.funnel.FunnelItem;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * 卸料漏斗物品：沿用 Create 的 {@link FunnelItem}（贴传送带放置时自动换成传送带形态），
 * 只额外补两行灰色说明。
 */
public class DumpFunnelItem extends FunnelItem {

    public DumpFunnelItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(Component.translatable("block.dimblend_blocks.dump_funnel.tooltip.1")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.dimblend_blocks.dump_funnel.tooltip.2")
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
