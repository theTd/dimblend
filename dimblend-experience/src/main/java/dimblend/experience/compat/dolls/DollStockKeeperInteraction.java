package dimblend.experience.compat.dolls;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlock;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class DollStockKeeperInteraction {
    public static void register() {
        NeoForge.EVENT_BUS.addListener(DollStockKeeperInteraction::onRightClick);
        NeoForge.EVENT_BUS.addListener(DollStockKeeperInteraction::onMount);
    }

    private static void onMount(EntityMountEvent event) {
        if (event.isMounting() && event.getEntityBeingMounted() instanceof SeatEntity seat
                && event.getLevel().getBlockState(seat.blockPosition()).getBlock() instanceof DollKeeperSeatBlock) {
            event.setCanceled(true);
        }
    }

    /** null also means an adjacent chunk is unknown: uniqueness must be proved without loading it. */
    @Nullable
    public static BlockPos uniqueTicker(Level level, BlockPos seat) {
        BlockPos result = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = seat.relative(direction);
            if (!level.hasChunkAt(candidate)) {
                return null;
            }
            if (level.getBlockState(candidate).getBlock() instanceof StockTickerBlock) {
                if (result != null) {
                    return null;
                }
                result = candidate;
            }
        }
        return result;
    }

    @Nullable
    public static DollKeeperSeatBlockEntity keeperFor(Level level, BlockPos ticker) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = ticker.relative(direction);
            if (level.hasChunkAt(candidate) && level.getBlockEntity(candidate) instanceof DollKeeperSeatBlockEntity keeper
                    && keeper.isValidKeeperFor(ticker)) {
                return keeper;
            }
        }
        return null;
    }

    /** Exposed for the interaction GameTests; validates again on the authoritative side. */
    public static boolean install(Level level, BlockPos pos, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        BlockState original = level.getBlockState(pos);
        if (player.isSpectator() || !player.mayBuild() || !VanillaDollItems.contains(held)
                || !(original.getBlock() instanceof SeatBlock seat) || seat instanceof DollKeeperSeatBlock
                || original.getBlock() != AllBlocks.SEATS.get(seat.getColor()).get()) {
            return false;
        }
        BlockPos ticker = uniqueTicker(level, pos);
        if (ticker == null) {
            return false;
        }
        if (level.isClientSide) {
            return true;
        }
        Direction facing = Direction.fromDelta(ticker.getX() - pos.getX(), 0, ticker.getZ() - pos.getZ());
        BlockState installed = DollKeeperSeats.seat(seat.getColor()).defaultBlockState()
                .setValue(DollKeeperSeatBlock.FACING, facing)
                .setValue(SeatBlock.WATERLOGGED, original.getValue(SeatBlock.WATERLOGGED));
        if (!level.setBlock(pos, installed, Block.UPDATE_ALL)) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof DollKeeperSeatBlockEntity keeper)) {
            level.setBlock(pos, original, Block.UPDATE_ALL);
            return false;
        }
        keeper.install(held);
        for (SeatEntity entity : level.getEntitiesOfClass(SeatEntity.class, new AABB(pos))) {
            entity.ejectPassengers();
            entity.discard();
        }
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        return true;
    }

    private static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSpectator()) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (level.getBlockState(pos).getBlock() instanceof DollKeeperSeatBlock) {
            // Consume even an invalid keeper click: never fall through to dyeing, sitting or doll placement.
            if (level.getBlockEntity(pos) instanceof DollKeeperSeatBlockEntity keeper
                    && keeper.isValidKeeperFor(keeper.tickerPos())) {
                StockTickerInteractionHandler.interactWithLogisticsManagerAt(player, level, keeper.tickerPos());
            }
        } else if (!install(level, pos, player, event.getHand())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }

    private DollStockKeeperInteraction() {}
}
