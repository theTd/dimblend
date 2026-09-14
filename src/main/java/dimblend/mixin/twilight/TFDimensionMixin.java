package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.init.TFDimension;

@Mixin(value = TFDimension.class, remap = false)
public abstract class TFDimensionMixin {
    @Inject(method = "isTwilightPortalDestination", at = @At("HEAD"), cancellable = true)
    private static void dimblend$rotatingIsPortalWorld(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (TwilightBand.isRotating(level)) {
            cir.setReturnValue(true);
        }
    }
}
