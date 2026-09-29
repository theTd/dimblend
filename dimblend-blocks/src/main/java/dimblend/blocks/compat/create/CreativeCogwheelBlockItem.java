package dimblend.blocks.compat.create;

import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.kinetics.simpleRelays.CogwheelBlockItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 创造模式齿轮物品：继承 Create 齿轮物品以保留其对角/嵌入连放 helper 的 UX
 * （helper 物品谓词是 {@code ICogWheel.isSmallCogItem} 一类的方块侧判定，
 * 本板块齿轮方块继承即命中），差异：
 * <ul>
 * <li>{@link #canPlace} 加创造门（与 {@code CreativeOnlyBlockItem} 同式）：
 * 仅创造玩家可走常规放置</li>
 * <li>{@link #onItemUseFirst} 加创造门：helper 连放走 {@code placeInWorld}，
 * 字节码核实其不经 canPlace/getStateForPlacement——非创造直接跳过 helper
 * 走默认放置链（被 canPlace 拒绝）；两条路径之外还有
 * {@link CreativeKineticGuard} 的 EntityPlaceEvent 兜底</li>
 * </ul>
 */
public class CreativeCogwheelBlockItem extends CogwheelBlockItem {

    public CreativeCogwheelBlockItem(CogWheelBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        Player player = context.getPlayer();
        return player != null && player.isCreative() && super.canPlace(context, state);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isCreative()) {
            return InteractionResult.PASS;
        }
        return super.onItemUseFirst(stack, context);
    }
}
