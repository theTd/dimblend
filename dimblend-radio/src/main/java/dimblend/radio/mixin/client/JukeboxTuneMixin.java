package dimblend.radio.mixin.client;

import dimblend.radio.client.RadioTuneClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Right-clicking an empty radio jukebox opens the tuning screen.
 *
 * <p>User requirement, 2026-11-03: "电台的tune 内容 移至 在唱片机进入电台状态后 右键唱片机砖块打开".
 *
 * <p><b>Why the injection is on the client and only acts there.</b> A screen is a client object;
 * putting this in the shared mixin config would load it on a dedicated server and fail. This file
 * is listed under the config's {@code client} array, so it never reaches a headless server.
 *
 * <p><b>Why the click is only intercepted when there is no disc.</b> A jukebox with a record has
 * vanilla behaviour worth keeping (insert, eject, comparator output), and the radio cannot even
 * be running then. {@code HAS_RECORD} is the same test {@link JukeboxGoggleMixin} uses, so the
 * goggles and this screen agree about what counts as a radio.
 *
 * <p><b>Why the client does not check the radio state.</b> That state lives on the server. The
 * click is simply forwarded and the server answers with a snapshot — or refuses and says why in
 * chat. Any local check would be a second source of truth that can drift.
 *
 * <p>Returning {@code SUCCESS} stops vanilla's own handling; a plain right-click on an empty
 * jukebox has no vanilla action to lose, so nothing is taken away.
 */
@Mixin(JukeboxBlock.class)
public abstract class JukeboxTuneMixin {

    @Inject(method = "useItemOn(Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;",
            at = @At("HEAD"),
            cancellable = true)
    private void dimblend$openTuneOnRightClick(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!level.isClientSide || state.getValue(JukeboxBlock.HAS_RECORD)) {
            return;
        }
        // A held disc is the player's way to load the record, not to tune: let vanilla take it.
        if (stack.has(net.minecraft.core.component.DataComponents.JUKEBOX_PLAYABLE)) {
            return;
        }
        if (!player.isSecondaryUseActive()) {
            RadioTuneClient.requestOpen(pos);
            cir.setReturnValue(ItemInteractionResult.SUCCESS);
        }
    }
}