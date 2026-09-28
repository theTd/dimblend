package dimblend.carwash.server;

import dimblend.carwash.chassis.ChassisBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 手持物品右键车架（GAME 总线，两端都触发）：
 * <ul>
 * <li>水桶 / 湿海绵：清洗一次</li>
 * <li>泥土：脏值 +32</li>
 * </ul>
 * 两端都取消事件，挡住倒水、放方块与伪装方块贴材质；实际改值只在服务端。
 */
public final class ChassisHandInteractions {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity().isSpectator()) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!ChassisBlocks.isChassis(level.getBlockState(pos))) {
            return;
        }
        ItemStack stack = event.getItemStack();
        boolean washing = stack.is(Items.WATER_BUCKET) || stack.is(Items.WET_SPONGE);
        boolean soiling = stack.is(Items.DIRT);
        if (!washing && !soiling) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
        if (level instanceof ServerLevel serverLevel) {
            if (washing) {
                ChassisWashing.washOnce(serverLevel, pos);
            } else {
                ChassisWashing.soil(serverLevel, pos);
            }
        }
    }

    private ChassisHandInteractions() {
    }
}
