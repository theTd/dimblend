package dimblend.experience.mixin.compat.dynlight;

import dimblend.experience.client.lighting.SableHeadlightLighting;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import toni.sodiumdynamiclights.DynamicLightSource;
import toni.sodiumdynamiclights.SodiumDynamicLights;

@Mixin(value = SodiumDynamicLights.class, remap = false)
public abstract class SableHeadlightFalloffMixin {
    @Redirect(method = "getDynamicLightLevel", at = @At(value = "INVOKE",
            target = "Ltoni/sodiumdynamiclights/SodiumDynamicLights;maxDynamicLightLevel(Lnet/minecraft/core/BlockPos;Ltoni/sodiumdynamiclights/DynamicLightSource;D)D"),
            require = 1, allow = 1)
    private double dimblend$headlightFalloff(BlockPos pos, DynamicLightSource source, double current) {
        return SableHeadlightLighting.maxLight(pos, source, current);
    }
}
