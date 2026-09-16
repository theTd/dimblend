package dimblend.mixin;

import dimblend.weather.ServerBandWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true)
    private void dimblend$bandIsRaining(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel && ServerBandWeather.locked()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true)
    private void dimblend$bandIsThundering(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel && ServerBandWeather.locked()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void dimblend$bandIsRainingAt(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel server && ServerBandWeather.clearAt(server, pos)) {
            cir.setReturnValue(false);
        }
    }
}
