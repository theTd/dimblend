package dimblend.experience.mixin.compat.dynlight;

import dimblend.experience.client.lighting.SableHeadlightLighting;
import dimblend.experience.client.lighting.SableHeadlightSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "top.leonx.dynlight.sodium.AbstractSodiumDynamicLightSource", remap = false)
public abstract class SableHeadlightRefreshMixin {
    @Inject(method = "sdl$getLuminance", at = @At("TAIL"), cancellable = true, require = 1, allow = 1)
    private void dimblend$useVeilLight(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof SableHeadlightSource source && source.dimblend$isHeadlight()) cir.setReturnValue(0);
    }

    @Inject(method = "markRemoved", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$removeVeilLight(CallbackInfo ci) {
        if ((Object) this instanceof SableHeadlightSource source) SableHeadlightLighting.remove(source);
    }
}
