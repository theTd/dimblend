package dimblend.mixin;

import dimblend.worldgen.EndIslandHeight;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Repairs the End island density field past the int-overflow cliff.
 *
 * <p>Vanilla {@code DensityFunctions.EndIslandDensityFunction#compute} feeds
 * {@code blockX / 8, blockZ / 8} into {@code getHeightValue}, which squares them
 * in <em>int</em>. The sum wraps negative once {@code (blockX/8)^2 + (blockZ/8)^2
 * >= 2^31} — a Euclidean radius of 370,728 blocks — {@code Mth.sqrt} yields NaN,
 * and the NaN survives clamp/max into the final density, so no island terrain
 * generates. In {@code dimblend:rotating} the End delegate runs at the
 * dimension's absolute X, putting every End band past region 181 (near corridor
 * Z) inside that dead annulus.</p>
 *
 * <p>The replacement is {@link EndIslandHeight}, a faithful vanilla copy whose
 * only difference is long arithmetic in the squared-distance term. Below the
 * overflow threshold it is bit-identical to vanilla, so existing chunks (and
 * the vanilla {@code minecraft:the_end} dimension below 370,728 blocks) are
 * unchanged; beyond it outer islands generate again from the untouched
 * noise-cell loop.</p>
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$EndIslandDensityFunction")
public abstract class EndIslandDensityFunctionMixin {
    @Shadow
    @Final
    private SimplexNoise islandNoise;

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void dimblend$overflowSafeIslandHeight(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(EndIslandHeight.density(
                EndIslandHeight.height(this.islandNoise, context.blockX() / 8, context.blockZ() / 8)));
    }
}