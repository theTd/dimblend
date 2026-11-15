package dimblend.carwash.server;

import dimblend.blocks.api.CreativeCopycatHiding;
import dimblend.carwash.chassis.ChassisBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 手持物品右键车架（GAME 总线，两端都触发）：
 * <ul>
 * <li>湿海绵：单击一块洗一次，不消耗（口径不变）</li>
 * <li>水桶：以右键格为中心的 {@code 5x5x5} 内每块带脏值的可见车架洗一次，生存/冒险扣一桶水
 * （回空桶），创造保留；范围内全干净则不消耗、也不倒水</li>
 * <li>泥土：脏值 +32</li>
 * </ul>
 * 两端都取消事件，挡住倒水、放方块与伪装方块贴材质；实际改值只在服务端。
 */
public final class ChassisHandInteractions {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSpectator()) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!ChassisBlocks.isChassis(level.getBlockState(pos))) {
            return;
        }
        // 隐藏车架交互穿透：不取消事件（水桶照常倒水、泥土照常放置）；
        // 隐藏期脏值冻结，洗/弄脏均无意义，且隐形方块不该吃掉玩家的点击
        if (CreativeCopycatHiding.isHidden(level, pos)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.is(Items.WET_SPONGE)) {
            // 湿海绵口径不变：只洗点击的那一块，海绵不消耗
            cancelClick(event, level);
            if (level instanceof ServerLevel serverLevel) {
                ChassisWashing.washOnce(serverLevel, pos);
            }
            return;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            // 水桶一律不倒水（取消右键），是否真的洗/扣水由服务端按 5x5x5 脏值判定
            cancelClick(event, level);
            if (level instanceof ServerLevel serverLevel) {
                emptyWaterBucket(serverLevel, player, stack, event.getHand(), pos);
            }
            return;
        }
        if (!stack.is(Items.DIRT)) {
            return;
        }
        cancelClick(event, level);
        if (level instanceof ServerLevel serverLevel) {
            ChassisWashing.soil(serverLevel, pos);
        }
    }

    /** 取消事件并回「客户端成功」：挡住倒水、放方块与伪装方块贴材质。 */
    private static void cancelClick(PlayerInteractEvent.RightClickBlock event, Level level) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
    }

    /**
     * 服务端水桶：{@code 5x5x5} 内有脏值才洗、出空桶音、记物品使用统计，并把水桶换成空桶；
     * 创造（{@code hasInfiniteMaterials()}）保留水桶。整包回推背包，纠正客户端本地预测。
     */
    private static void emptyWaterBucket(ServerLevel level, Player player, ItemStack stack, InteractionHand hand,
            BlockPos pos) {
        if (!ChassisWashing.canWashArea5x5x5(level, pos)) {
            return;
        }
        ChassisWashing.washArea5x5x5(level, pos);
        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
        if (player.hasInfiniteMaterials()) {
            return;
        }
        ItemStack emptied = ItemUtils.createFilledResult(stack, player, new ItemStack(Items.BUCKET));
        player.setItemInHand(hand, emptied);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.sendAllDataToRemote();
        }
    }

    private ChassisHandInteractions() {
    }
}
