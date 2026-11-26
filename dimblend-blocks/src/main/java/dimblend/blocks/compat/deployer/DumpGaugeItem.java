package dimblend.blocks.compat.deployer;

import java.util.List;
import java.util.function.Supplier;

import net.liukrast.deployer.lib.logistics.board.LogisticallyLinkedPanelBlockItem;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** 卸料仪表的物品形态：完全沿用 Deployer 的面板物品逻辑，只补一行说明。 */
public class DumpGaugeItem extends LogisticallyLinkedPanelBlockItem {

    public DumpGaugeItem(Supplier<PanelType<?>> type, Properties properties) {
        super(type, properties);
    }

    /** 面板物品默认会沿用 Create 工厂仪表方块的名字，这里换成自己的键。 */
    @Override
    public String getDescriptionId() {
        return "block.dimblend_blocks.dump_gauge";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("block.dimblend_blocks.dump_gauge.tooltip.1")
                .withStyle(ChatFormatting.GRAY));
    }
}
