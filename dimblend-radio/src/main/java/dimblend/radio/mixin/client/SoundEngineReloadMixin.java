package dimblend.radio.mixin.client;

import dimblend.radio.client.RadioController;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidate channels before reload destroys them, without treating pre-buffered audio as EOF. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineReloadMixin {
    @Inject(method = "reload()V", at = @At("HEAD"))
    private void dimblend$radioReload(CallbackInfo ci) {
        RadioController.onSoundReload();
    }
}
