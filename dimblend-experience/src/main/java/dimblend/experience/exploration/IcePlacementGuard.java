package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 冰放置禁令（rotating 内）：生存模式玩家不能放置三种冰
 * （{@code Blocks.ICE}/{@code PACKED_ICE}/{@code BLUE_ICE}，不含 frosted_ice）；
 * 创造模式豁免。与冰破坏产水配套：冰在 rotating 内是消耗性水源获取途径，
 * 不允许玩家把它当可搬运的方块使用。
 *
 * <p>拦截点是 {@code PlayerInteractEvent.RightClickBlock} 的 useItem 闸
 * （{@code ServerPlayerGameMode#useItemOn} 内 {@code event.getUseItem().isFalse()}
 * 直接跳过 {@code useOn}，源码核实）：只挡物品使用，保留对方块本身的交互
 * （手持冰开箱不被误伤）。事件双端触发——客户端同步拒绝可避免放置预测的
 * 幽灵块/假扣物品（G5 记录的 EntityPlaceEvent 取消幽灵问题不适用于本前置
 * 拦截点）；SERVER 配置经 ConfigSync 同步可读，读取前先 {@code Config.isLoaded()}
 * 守卫。</p>
 *
 * <p>已知残余通道（拍板接受）：Create 部署器等假玩家不走 RightClickBlock，
 * 仍可放冰——只挡生存玩家。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class IcePlacementGuard {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof BlockItem blockItem)) {
            return;
        }
        Block block = blockItem.getBlock();
        if (block != Blocks.ICE && block != Blocks.PACKED_ICE && block != Blocks.BLUE_ICE) {
            return;
        }
        if (!Config.isLoaded() || !Config.ICE_PLACEMENT_BAN.get()) {
            return;
        }
        if (!RotatingDimension.is(event.getLevel())) {
            return;
        }
        if (event.getEntity().isCreative()) {
            return;
        }
        event.setUseItem(TriState.FALSE);
    }

    private IcePlacementGuard() {
    }
}
