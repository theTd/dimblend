package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dimblend.fluid.NetherLavaFlow;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Nether lava speed (tick delay 10, flow distance 4, drop-off 1) for the nether and
 * Voidscape bands of {@code dimblend:rotating} without making the whole dimension
 * ultrawarm. See {@link NetherLavaFlow}.
 */
@Mixin(LavaFluid.class)
public abstract class LavaFluidUltrawarmMixin {
    @WrapOperation(
            method = {"getSlopeFindDistance", "getDropOff", "getTickDelay"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/dimension/DimensionType;ultraWarm()Z"
            )
    )
    private boolean dimblend$netherBandLavaFlow(
            DimensionType type,
            Operation<Boolean> original,
            @Local(argsOnly = true) LevelReader level
    ) {
        return original.call(type) || NetherLavaFlow.active(level);
    }
}
