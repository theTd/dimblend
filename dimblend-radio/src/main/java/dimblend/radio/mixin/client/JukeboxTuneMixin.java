package dimblend.radio.mixin.client;

import dimblend.radio.client.RadioTuneClient;
import dimblend.radio.net.ClientRadioState;
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
 * Right-clicking an empty radio jukebox with an empty main hand opens the tuning screen.
 *
 * <p>User requirement, 2026-11-03: "电台的tune 内容 移至 在唱片机进入电台状态后 右键唱片机砖块打开".
 * Narrowed 2026-11-25: the screen is the reward for an <b>empty main hand</b> right-click, and only
 * on a jukebox that is already a radio — any other click belongs to vanilla.
 *
 * <p><b>Why the injection is on the client and only acts there.</b> A screen is a client object;
 * putting this in the shared mixin config would load it on a dedicated server and fail. This file
 * is listed under the config's {@code client} array, so it never reaches a headless server.
 *
 * <p><b>Why the click is only intercepted when there is no disc, the main hand is empty and the
 * jukebox is a radio.</b> A jukebox with a record has vanilla behaviour worth keeping (insert,
 * eject, comparator output), and the radio cannot even be running then. A disc in either hand is
 * the player's way to load the record — vanilla inserts it, and swallowing that click would make
 * the jukebox unable to take a disc. A plain jukebox that is not a radio has nothing to tune, and
 * a screen opened over vanilla's own click is the bug this gate closes. {@code HAS_RECORD} is the
 * same test {@link JukeboxGoggleMixin} uses, so the goggles and this screen agree about what
 * counts as a radio. The radio-state test is the client's cached view, which is the only truth a
 * client has; the server re-checks it anyway ({@code RadioTuneService#open}).
 *
 * <p>Returning {@code SUCCESS} stops vanilla's own handling; a plain right-click on an empty
 * jukebox with an empty hand has no vanilla action to lose, so nothing is taken away.
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
            cancellable = true,
            require = 1,
            allow = 1)
    private void dimblend$openTuneOnRightClick(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!level.isClientSide || state.getValue(JukeboxBlock.HAS_RECORD)) {
            return;
        }
        // Empty main hand only: a held disc in either hand is the player's way to load the record,
        // and an off-hand swing is vanilla's business.
        if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty()) {
            return;
        }
        // Sneak-right-click is "place/interact deliberately"; do not steal it for a GUI.
        if (player.isSecondaryUseActive()) {
            return;
        }
        if (!ClientRadioState.view().containsKey(
                new ClientRadioState.Key(level.dimension().location().toString(), pos.immutable()))) {
            return;
        }
        RadioTuneClient.requestOpen(pos);
        cir.setReturnValue(ItemInteractionResult.SUCCESS);
    }
}