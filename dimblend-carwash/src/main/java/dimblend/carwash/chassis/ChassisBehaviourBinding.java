package dimblend.carwash.chassis;

import com.simibubi.create.api.event.BlockEntityBehaviourEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 给伪装板/伪装半砖的 BE 挂脏值行为（GAME 总线）。Create 在 BE 首次读 NBT 时发此事件，
 * 行为随后参与同一次读取，存盘数据与客户端同步包因此能落到行为上。
 * BE 类型按注册名解析，未注册（dimblend-blocks 未启用伪装方块）时跳过。
 */
public final class ChassisBehaviourBinding {

    @SubscribeEvent
    public static void onBlockEntityBehaviours(BlockEntityBehaviourEvent event) {
        if (ChassisBlocks.CREATIVE_PANEL_BLOCK_ENTITY.isBound()) {
            event.forType(ChassisBlocks.CREATIVE_PANEL_BLOCK_ENTITY.get(),
                    be -> event.attach(new ChassisGrimeBehaviour(be)));
        }
        if (ChassisBlocks.CREATIVE_SLAB_BLOCK_ENTITY.isBound()) {
            event.forType(ChassisBlocks.CREATIVE_SLAB_BLOCK_ENTITY.get(),
                    be -> event.attach(new ChassisGrimeBehaviour(be)));
        }
    }

    private ChassisBehaviourBinding() {
    }
}
