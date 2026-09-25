package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.init.TFDimension;

@Mixin(value = TFDimension.class, remap = false)
public abstract class TFDimensionClientMixin {
    @Inject(method = "isTwilightWorldOnClient", at = @At("HEAD"), cancellable = true)
    private static void dimblend$onlyTwilightBand(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (!TwilightBand.isRotating(level)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && TwilightBand.isTwilightPos(level, minecraft.player.blockPosition())) {
            cir.setReturnValue(true);
        } else {
            cir.setReturnValue(false);
        }
    }
}
