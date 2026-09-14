package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import twilightforest.events.ProgressionEvents;

@Mixin(value = ProgressionEvents.class, remap = false)
public abstract class ProgressionEventsMixin {
    @Inject(
            method = "checkForPortalCreation(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;F)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void dimblend$onlyTwilightBand(ServerPlayer player, Level level, float range, CallbackInfo ci) {
        if (TwilightBand.isRotating(level) && !TwilightBand.isTwilightPos(level, player.blockPosition())) {
            ci.cancel();
        }
    }
}