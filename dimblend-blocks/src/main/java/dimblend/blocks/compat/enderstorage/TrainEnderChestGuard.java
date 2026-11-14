package dimblend.blocks.compat.enderstorage;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 列车末影物流箱的服务端纵深防御：正常手持/机械手放置已在 {@link TrainEnderChestItem#canPlace}
 * 被双端拦截；若仍有绕过 {@code BlockItem#canPlace} 但触发 {@link BlockEvent.EntityPlaceEvent}
 * 的非标准放置落在 Sable 子层级之外，则在本服务端 tick 末拆除并按 {@link Block#getDrops}
 * 保留频率/钥匙返还（真人回背包、机器掉落），避免客户端预测不同步或吃物品。
 */
public final class TrainEnderChestGuard {

    private record PendingTeardown(ServerLevel level, BlockPos pos, Entity placer) {
    }

    private static final List<PendingTeardown> PENDING = new ArrayList<>();

    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState placed = event.getBlockSnapshot().getCurrentState();
        if (placed.getBlock() != TrainEnderChests.TRAIN_ENDER_CHEST.get()) {
            return;
        }
        Entity entity = event.getEntity();
        BlockPos pos = event.getPos().immutable();
        if (SablePlacementCheck.canPlace(level, pos, entity)) {
            return;
        }
        PENDING.add(new PendingTeardown(level, pos, entity));
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<PendingTeardown> batch = List.copyOf(PENDING);
        PENDING.clear();
        for (PendingTeardown pending : batch) {
            teardown(pending);
        }
    }

    private static void teardown(PendingTeardown pending) {
        ServerLevel level = pending.level();
        BlockPos pos = pending.pos();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() != TrainEnderChests.TRAIN_ENDER_CHEST.get()) {
            return;
        }
        Entity placer = pending.placer();
        boolean realPlayer = placer != null && placer.getClass() == ServerPlayer.class && !placer.isRemoved();
        if (!realPlayer) {
            level.destroyBlock(pos, true, placer);
            return;
        }
        ServerPlayer player = (ServerPlayer) placer;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> refunds = Block.getDrops(state, level, pos, blockEntity, player, ItemStack.EMPTY);
        level.destroyBlock(pos, false, player);
        for (ItemStack refund : refunds) {
            player.getInventory().placeItemBackInInventory(refund);
        }
    }

    private TrainEnderChestGuard() {
    }
}
