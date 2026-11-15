package dimblend.experience.mixin.compat.firefightingadd;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mikoalopex.createfirefightingadd.content.kinetics.pump.HighPressurePumpBlockEntity;
import dimblend.experience.compat.firefightingadd.HighPressurePumpTuning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 将高压水泵的实际流体加压传输距离放大 16 倍。
 */
@Mixin(value = HighPressurePumpBlockEntity.class, remap = false)
public abstract class HighPressurePumpRangeMixin {

    @ModifyReturnValue(method = "CreateFireFightingAdd$getPumpRange()I", at = @At("RETURN"), require = 1, allow = 1)
    private int dimblend$scaleHighPressurePumpRange(int original) {
        return HighPressurePumpTuning.scalePumpRange(original);
    }
}
