package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import twilightforest.client.event.LockedBiomeToastHandler;

/**
 * The locked-biome toast pops up for any non-creative player standing in a locked biome, every
 * 5 ticks. Riders on a Sable carriage keep the biome under them while the train crosses the whole
 * locked region, so a long line would fire the toast repeatedly for a player who cannot leave the
 * carriage anyway. Skipped for {@link TwilightBand#isOnSable} riders in
 * {@code dimblend:rotating}; the vanilla toast state machine is left untouched for everyone else.
 */
@Mixin(value = LockedBiomeToastHandler.class, remap = false)
public abstract class LockedBiomeToastHandlerMixin {

    @Inject(method = "tickLockedToastLogic", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void dimblend$skipOnSable(ClientTickEvent.Post event, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null
                && minecraft.player != null
                && TwilightBand.isRotating(minecraft.level)
                && TwilightBand.isOnSable(minecraft.player)) {
            ci.cancel();
        }
    }
}
