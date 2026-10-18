package dimblend.experience.mixin.compat.dynlight;

import dimblend.experience.client.lighting.SableHeadlightLighting;
import dimblend.experience.client.lighting.SableHeadlightSource;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "top.leonx.dynlight.sodium.AbstractSodiumDynamicLightSource", remap = false)
public abstract class SableHeadlightRefreshMixin {
    @Shadow protected Vec3 position;
    @Shadow private int previousLuminance;
    @Shadow private LongOpenHashSet trackedLitChunkPositions;
    @Unique private double dimblend$previousRadius = Double.NaN;
    @Unique private boolean dimblend$previousHeadlight;

    @Inject(method = "sdl$getLuminance", at = @At("TAIL"), cancellable = true, require = 1, allow = 1)
    private void dimblend$brightness(CallbackInfoReturnable<Integer> cir) {
        if (SableHeadlightLighting.selected(this)) cir.setReturnValue(SableHeadlightLighting.brightness(cir.getReturnValueI()));
    }

    @Inject(method = "sodiumdynamiclights$updateDynamicLight", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$refreshChangedRadius(LevelRenderer renderer, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof SableHeadlightSource)) return;
        boolean selected = SableHeadlightLighting.selected(this);
        double radius = selected ? SableHeadlightLighting.radius() : 7.75D;
        if (selected != dimblend$previousHeadlight || radius != dimblend$previousRadius) {
            // Let the existing update lifecycle rebuild both old and new footprints, even while stationary.
            previousLuminance = -1;
            dimblend$previousHeadlight = selected;
            dimblend$previousRadius = radius;
        }
    }

    @Inject(method = "trackLitChunks", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$trackHeadlightChunks(LevelRenderer renderer, LongOpenHashSet next, CallbackInfo ci) {
        if (SableHeadlightLighting.selected(this)) {
            SableHeadlightLighting.trackChunks(renderer, position, trackedLitChunkPositions, next);
            ci.cancel();
        }
    }
}
