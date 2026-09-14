package dimblend.mixin;

import dimblend.compat.TwilightBand;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobSunBurnMixin {
    @Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
    private void dimblend$noSunInTwilightBand(CallbackInfoReturnable<Boolean> cir) {
        Mob self = (Mob) (Object) this;
        if (TwilightBand.isTwilightPos(self.level(), self.blockPosition())) {
            cir.setReturnValue(false);
        }
    }
}
