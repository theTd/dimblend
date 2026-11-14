package dimblend.blocks.compat.enderstorage;

import java.util.List;

import codechicken.enderstorage.item.ItemEnderStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 列车末影物流箱物品：
 * <ul>
 *   <li>继承 {@link ItemEnderStorage} 完整保留频率组件读写与放置时频率写入 BE；</li>
 *   <li>在 {@link #canPlace} 双端拦截非 Sable 子层级的放置（零幽灵方块、零物品吞没）；</li>
 *   <li>在悬浮提示首行追加灰色描述「仅可在列车上使用」，随后展示原有的私有主人与三色频率。</li>
 * </ul>
 */
public class TrainEnderChestItem extends ItemEnderStorage {

    public TrainEnderChestItem(Block block) {
        super(block);
    }

    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        return SablePlacementCheck.canPlace(context.getLevel(), context.getClickedPos(), context.getPlayer())
                && super.canPlace(context, state);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flagIn) {
        tooltip.add(Component.translatable("block.dimblend_blocks.train_ender_chest.tooltip")
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, ctx, tooltip, flagIn);
    }
}
