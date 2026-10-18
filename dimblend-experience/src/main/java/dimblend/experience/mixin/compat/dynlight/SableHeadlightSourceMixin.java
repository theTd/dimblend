package dimblend.experience.mixin.compat.dynlight;

import dimblend.experience.client.lighting.SableHeadlightLighting;
import dimblend.experience.client.lighting.SableHeadlightSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "top.leonx.dynlight.sodium.sable.SableDynLightSource", remap = false)
public abstract class SableHeadlightSourceMixin implements SableHeadlightSource {
    @Shadow @Final private BlockPos plotPos;
    @Unique private volatile boolean dimblend$headlight;

    @Inject(method = "<init>", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$classifyInitial(Level level, Object subLevel, BlockPos pos, int luminance, CallbackInfo ci) {
        dimblend$headlight = SableHeadlightLighting.matches(level, pos);
    }

    @Inject(method = "update", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$classifyUpdated(Level level, Object subLevel, int luminance, CallbackInfo ci) {
        dimblend$headlight = SableHeadlightLighting.matches(level, plotPos);
    }

    @Override
    public boolean dimblend$isHeadlight() {
        return dimblend$headlight;
    }
}
