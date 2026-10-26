package dimblend.experience.compat.create.observer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Both sides of the observer blaze's right-click: entering with a spyglass on a burner that sits on a Sable vehicle,
 * and leaving with sneak + an empty main hand. Both are handled here, in
 * {@code PlayerInteractEvent.RightClickBlock}, which fires on either side before vanilla's sneaking branch; vanilla
 * skips the block use entirely when a sneaking player holds anything in either hand
 * ({@code ServerPlayerGameMode.useItemOn}, {@code MultiPlayerGameMode.performUseItemOn}), so the exit would be
 * unreachable for a player whose offhand holds an item. Cancelling here also keeps the spyglass from zooming.
 *
 * <p>Registered from the mod constructor only while Create and Sable are loaded -- the condition is part of the
 * event, not a runtime check, because the handler resolves Create and Sable types.</p>
 */
public final class ObserverBlazeInteraction {
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ObserverBlazeInteraction::onRightClickBlock);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        Player player = event.getEntity();
        BlockPos pos = event.getPos();
        if (ObserverBlazeSwap.canLeave(level, pos, player)) {
            cancel(event, level);
            if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
                ObserverBlazeSwap.leave(server, pos, serverPlayer);
            }
            return;
        }
        if (!ObserverBlazeSwap.canEnter(level, pos, player, event.getItemStack())) {
            return;
        }
        cancel(event, level);
        if (level instanceof ServerLevel server) {
            ObserverBlazeSwap.enter(server, pos, player, event.getHand());
        }
    }

    /** Consumes the click on both sides with the same result, so the client predicts exactly what the server does. */
    private static void cancel(PlayerInteractEvent.RightClickBlock event, Level level) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }

    private ObserverBlazeInteraction() {
    }
}
