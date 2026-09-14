package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "twilightforest.events.EntityEvents$ExtendedEndermanTakeBlockGoal", remap = false)
public abstract class EndermanTakeBlockGoalMixin {
    @Shadow
    @Final
    private EnderMan enderman;

    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
    private void dimblend$noGrabInTwilightBand(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && TwilightBand.isTwilightPos(this.enderman.level(), this.enderman.blockPosition())) {
            cir.setReturnValue(false);
        }
    }
}
