package dimblend.experience.mixin.compat.accessories;

import dimblend.experience.compat.accessories.AccessoriesSurvivalRules;
import io.wispforest.accessories.networking.server.ScreenOpen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side backstop: even if a client sends {@code ScreenOpen} while in survival (modified
 * client, or a stale request), the packet is dropped instead of opening the accessory menu.
 */
@Mixin(value = ScreenOpen.class, remap = false)
public abstract class AccessoriesScreenOpenSurvivalMixin {

    @Inject(method = "handlePacket(Lio/wispforest/accessories/networking/server/ScreenOpen;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private static void dimblend$blockSurvivalScreenOpen(ScreenOpen packet, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer
                && AccessoriesSurvivalRules.isSurvivalMode(serverPlayer.gameMode.getGameModeForPlayer())) {
            ci.cancel();
        }
    }
}
