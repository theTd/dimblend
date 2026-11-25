package dimblend.blocks.compat.rail;

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
 * 列车加压管道的服务端纵深防御。
 *
 * <p>正常手持/机械手放置已被 {@link TrainPressuredTubeItem#canPlace} 双端拦下；若仍有绕过
 * {@code BlockItem#canPlace} 却触发 {@link BlockEvent.EntityPlaceEvent} 的放置落在 Sable
 * 子层级之外，就在本 tick 末拆除：真人玩家直接退回背包，机器则走 {@link Block#getDrops} 掉地上。
 * 这样客户端预测与真实世界不会长期不一致，也不会吞物品。</p>
 */
public final class TrainTubePlacementGuard {

    private record PendingTeardown(ServerLevel level, BlockPos pos, Entity placer) {
    }

    private static final List<PendingTeardown> PENDING = new ArrayList<>();

    private TrainTubePlacementGuard() {
    }

    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState placed = event.getBlockSnapshot().getCurrentState();
        if (!TrainTubes.isTube(placed)) {
            return;
        }
        BlockPos pos = event.getPos().immutable();
        if (TrainTubeSable.canPlace(level, pos, event.getEntity())) {
            return;
        }
        PENDING.add(new PendingTeardown(level, pos, event.getEntity()));
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<PendingTeardown> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        for (PendingTeardown pending : batch) {
            teardown(pending);
        }
    }

    private static void teardown(PendingTeardown pending) {
        ServerLevel level = pending.level();
        BlockPos pos = pending.pos();
        BlockState state = level.getBlockState(pos);
        if (!TrainTubes.isTube(state)) {
            return;
        }
        Entity placer = pending.placer();
        boolean realPlayer = placer instanceof ServerPlayer player && !player.isRemoved();
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
}
