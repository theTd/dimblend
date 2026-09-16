package dimblend.mixin.voidscape;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Voidscape's spire scan rejects every column that reaches Y=0 ({@code if (p.getY() <= 0)}
 * in {@code checkForRoom}) because Y=0 is the floor of its own dimension ({@code min_y: 0}).
 * The rotating dimension wraps {@code voidscape:void} in {@code dimblend:y_shifted} with
 * {@code y_offset: -64}, so the whole antispire layer — biome bound {@code layerBottomDownwardsStart}
 * 32, shifted to Y=-32 and hanging down to the world floor — sits below Y=0 and every
 * candidate column is rejected at the first block: no {@code voidscape:antispire} can
 * generate at all.
 *
 * <p>Measuring the column against the level's own floor instead keeps Voidscape's own
 * dimension byte-for-byte identical (floor 0) and makes every antispire column eligible
 * again in the rotating dimension: with the fix, inverted {@code checkForRoom} calls reach
 * {@code OK} there and spires are placed again (probe: 2 tips / 49 chunks in one rotating
 * area vs 3 tips / 49 chunks in {@code voidscape:void}; multi-area samples 1 / 75 vs
 * 3 / 50). Whether the rate is systematically lower is not established by those samples —
 * see the open fluid-surface item in {@code docs/generation-rules.md}.
 *
 * <p>The sibling bound in {@code place} ({@code getMinBuildHeight() + 32} in the rotating
 * dimension, since the wrapped biome source is not a {@code LayeredBiomeProvider}) agrees
 * with this floor only while the applied {@code y_offset} equals {@code min_y} — see the
 * invariant asserted in {@code VoidscapeYShiftContractTest}.
 */
@Mixin(targets = "tamaized.voidscape.features.SpireFeature", remap = false)
public abstract class SpireFeatureMixin {
    @ModifyExpressionValue(
            method = "checkForRoom",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;getY()I")
    )
    private int dimblend$columnYAboveWorldFloor(int y, @Local(argsOnly = true) WorldGenLevel level) {
        return y - level.getMinBuildHeight();
    }
}
