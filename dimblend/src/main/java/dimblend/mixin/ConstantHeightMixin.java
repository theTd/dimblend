package dimblend.mixin;

import dimblend.worldgen.WorldGenerationContextExtension;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ConstantHeight.class)
public abstract class ConstantHeightMixin {
    @Inject(method = "sample", at = @At("RETURN"), cancellable = true)
    private void dimblend$shiftCarverSample(RandomSource random, WorldGenerationContext context, CallbackInfoReturnable<Integer> cir) {
        if (!(context instanceof CarvingContext)) {
            return;
        }
        int offset = ((WorldGenerationContextExtension) context).dimblend$absoluteOffset();
        if (offset != 0) {
            cir.setReturnValue(cir.getReturnValueI() + offset);
        }
    }
}
