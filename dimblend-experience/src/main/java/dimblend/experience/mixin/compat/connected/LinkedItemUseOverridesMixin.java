package dimblend.experience.mixin.compat.connected;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.block.ItemUseOverrides;
import com.simibubi.create.foundation.utility.BlockHelper;
import dimblend.experience.compat.connected.LinkedTransmitterInteraction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemUseOverrides.class)
public abstract class LinkedItemUseOverridesMixin {

    @Inject(method = "onBlockActivated", at = @At("HEAD"), cancellable = true, require = 1)
    private static void dimblend$usePacketHit(PlayerInteractEvent.RightClickBlock event, CallbackInfo ci) {
        if (AllItems.WRENCH.isIn(event.getItemStack())) {
            return;
        }
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!LinkedTransmitterInteraction.supports(state, event.getEntity())) {
            return;
        }
        BlockHitResult hit = LinkedTransmitterInteraction.packetHit(event);
        if (hit == null) {
            return;
        }
        InteractionResult result = BlockHelper.invokeUse(state, event.getLevel(), event.getEntity(), event.getHand(), hit);
        if (result.consumesAction()) {
            event.setCanceled(true);
            event.setCancellationResult(result);
        }
        ci.cancel();
    }
}
