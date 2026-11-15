package dimblend.experience.mixin.compat.firefightingadd;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.FluidPropagator;
import dimblend.experience.compat.firefightingadd.HighPressurePumpTuning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create 的 {@link FluidPropagator#propagateChangedPipe} 默认仅在普通水泵距离（16 格）内向未加压管道传播变更；
 * 放大该搜索上限以保证远端（至 512 格）拆装管道或储罐时能正确唤醒高压水泵重新建网。
 */
@Mixin(value = FluidPropagator.class, remap = false)
public abstract class FluidPropagatorHighPressureRangeMixin {

    @WrapOperation(
            method = "propagateChangedPipe",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/fluids/FluidPropagator;getPumpRange()I"
            ),
            require = 1,
            allow = 1
    )
    private static int dimblend$expandUnpressurizedPipeWakeRange(Operation<Integer> original) {
        return HighPressurePumpTuning.scalePipeWakeRange(original.call());
    }
}
