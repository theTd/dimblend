package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.client.ClientWeatherLock;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client half of the per-player weather lock. {@code getRainLevel} feeds rain
 * rendering, rain sounds, sky/fog/cloud tint, {@code isRaining}/{@code isThundering},
 * and thunder (which multiplies by rain). Returning 0 is the visual mask;
 * {@code isClientSide} keeps the integrated server on the real rain level.
 */
@Mixin(Level.class)
public abstract class LevelRainLevelClientMixin {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void dimblend$clientClearRain(float delta, CallbackInfoReturnable<Float> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide()
                && self.dimension() == DimBlendRegistries.ROTATING_LEVEL
                && ClientWeatherLock.active()) {
            cir.setReturnValue(0.0F);
        }
    }
}
