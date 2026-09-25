package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import twilightforest.client.TwilightForestRenderInfo;

/**
 * {@code isFoggyAt} uses an absolute Y&gt;20 ceiling ("above the dark forest").
 * After the rotating-dimension +64 lift that ceiling must move to 84, otherwise
 * former cave/canopy fog never triggers on the raised terrain.
 */
@Mixin(value = TwilightForestRenderInfo.class, remap = false)
public abstract class TwilightForestRenderInfoMixin {
    @ModifyConstant(method = "isFoggyAt", constant = @Constant(doubleValue = 20.0))
    private double dimblend$liftFoggyCeiling(double original) {
        Minecraft minecraft = Minecraft.getInstance();
        return TwilightBand.liftedEffectY(
                original, minecraft.level != null && TwilightBand.isRotating(minecraft.level));
    }
}
