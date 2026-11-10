package dimblend.experience.compat.create.stockkeeper;

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlock;
import dimblend.experience.compat.dolls.DollKeeperSeatBlock;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Bridges the original doll item placement into Create's public seat lifecycle. */
public final class DollSeatPlacement {
    public static final ResourceLocation DOLL_ITEM =
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_doll", "doll_entity_item");

    private DollSeatPlacement() { }

    public static boolean targetsStockKeeperSeat(Level level, BlockPos seatPos, Player player, ItemStack stack) {
        var seatState = level.getBlockState(seatPos);
        if (player == null || player.isSpectator() || player.isShiftKeyDown() || player instanceof FakePlayer
                || !DOLL_ITEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                || !(seatState.getBlock() instanceof SeatBlock)
                // A proxy keeper seat stores its doll as an item; seating a real DollEntity on it would
                // be a second, conflicting keeper model. Only ordinary Create seats accept this bridge.
                || seatState.getBlock() instanceof DollKeeperSeatBlock) return false;
        // Same eight neighboring cells and uniqueness rule as getStockTickerPosition, before a doll exists.
        int tickers = 0;
        for (Direction side : Iterate.horizontalDirections) {
            for (int yOffset : Iterate.zeroAndOne) {
                if (level.getBlockState(seatPos.relative(side).above(yOffset)).getBlock() instanceof StockTickerBlock) {
                    if (++tickers > 1) return false;
                }
            }
        }
        return tickers == 1;
    }

    public static boolean canPlace(UseOnContext context) {
        return context.getHand() == InteractionHand.MAIN_HAND
                && targetsStockKeeperSeat(context.getLevel(), context.getClickedPos(), context.getPlayer(), context.getItemInHand())
                && !SeatBlock.isSeatOccupied(context.getLevel(), context.getClickedPos());
    }

    public static void seatSpawnedDoll(UseOnContext context, Entity doll) {
        if (context.getLevel().isClientSide() || !DollStockKeeperCompat.isDoll(doll) || !canPlace(context)) return;
        SeatBlock.sitDown(context.getLevel(), context.getClickedPos(), doll);
        Entity seat = doll.getVehicle();
        if (seat != null) seat.positionRider(doll);
    }
}