package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.client.ClientWeatherLock;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client half of the per-player weather lock. {@code getRainLevel} feeds rain
 * rendering, rain sounds, sky/fog/cloud tint, {@code isRaining}/{@code isThundering}.
 * {@code getThunderLevel} is zeroed too because {@code getSkyColor} tints by
 * thunder independently of rain. Returning 0 is the visual mask;
 * {@code isClientSide} keeps the integrated server on the real rain level.
 */
@Mixin(Level.class)
public abstract class LevelRainLevelClientMixin {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void dimblend$clientClearRain(float delta, CallbackInfoReturnable<Float> cir) {
        if (dimblend$clientWeatherLocked()) {
            cir.setReturnValue(0.0F);
        }
    }

    /**
     * {@code getSkyColor} tints by thunder independently of rain. Zero both
     * or a thundering overworld still muddies a locked band's sky.
     */
    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void dimblend$clientClearThunder(float delta, CallbackInfoReturnable<Float> cir) {
        if (dimblend$clientWeatherLocked()) {
            cir.setReturnValue(0.0F);
        }
    }

    @Unique
    private boolean dimblend$clientWeatherLocked() {
        Level self = (Level) (Object) this;
        return self.isClientSide()
                && self.dimension() == DimBlendRegistries.ROTATING_LEVEL
                && ClientWeatherLock.active();
    }
}
