package dimblend.experience.mixin.compat.create.stockkeeper;

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import dimblend.experience.compat.create.stockkeeper.DollSeatPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SeatBlock.class)
public abstract class DollSeatBlockInteractionMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$letDollItemPlace(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!DollSeatPlacement.targetsStockKeeperSeat(level, pos, player, stack)) return;
        // Consume the click without running the item or ejecting an occupant; FAIL would still fall through to item use.
        cir.setReturnValue(hand == InteractionHand.MAIN_HAND && !SeatBlock.isSeatOccupied(level, pos)
                ? ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION : ItemInteractionResult.CONSUME);
    }
}