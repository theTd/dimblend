package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dimblend.experience.exploration.BedRestRules;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rest never triggers the world's sleep time jump, forced wake-up, or weather reset where
 *  {@link BedRestRules#suppressesTimeSkip} says so (overworld always; rotating only daytime
 *  naps — night/thunder sleep in rotating skips to morning vanilla-style). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBedRestMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/SleepStatus;areEnoughSleeping(I)Z"), require = 1, allow = 1)
    private boolean dimblend$disableRestTimeSkip(boolean enoughSleeping) {
        return enoughSleeping && !BedRestRules.suppressesTimeSkip((ServerLevel) (Object) this);
    }

    @Inject(method = "announceSleepStatus", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$suppressRestTimeSkipAnnouncement(CallbackInfo ci) {
        if (BedRestRules.suppressesTimeSkip((ServerLevel) (Object) this)) ci.cancel();
    }
}
