package dimblend.blocks.compat.copycats;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 创造模式伪装方块专用物品：仅创造模式玩家可放置（生存玩家与无玩家来源的
 * 机器放置一律拒绝）。
 */
public class CreativeOnlyBlockItem extends BlockItem {

    public CreativeOnlyBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        Player player = context.getPlayer();
        return player != null && player.isCreative() && super.canPlace(context, state);
    }
}
