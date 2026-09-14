package dimblend.mixin;

import dimblend.time.ServerBandTime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelDayTimeMixin {
    @Inject(method = "isDay", at = @At("HEAD"), cancellable = true)
    private void dimblend$bandIsDay(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel && ServerBandTime.locked()) {
            cir.setReturnValue(ServerBandTime.lockedIsDay());
        }
    }

    @Inject(method = "isNight", at = @At("HEAD"), cancellable = true)
    private void dimblend$bandIsNight(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel && ServerBandTime.locked()) {
            cir.setReturnValue(!ServerBandTime.lockedIsDay());
        }
    }

    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void dimblend$bandDayTime(CallbackInfoReturnable<Long> cir) {
        if ((Object) this instanceof ServerLevel && ServerBandTime.locked()) {
            cir.setReturnValue(ServerBandTime.lockedDayTime());
        }
    }
}
