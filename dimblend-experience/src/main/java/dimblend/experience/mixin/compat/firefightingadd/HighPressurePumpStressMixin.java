package dimblend.experience.mixin.compat.firefightingadd;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.api.stress.BlockStressValues;
import dimblend.experience.compat.firefightingadd.HighPressurePumpTuning;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 将高压水泵的应力需求放大 16 倍（8.0 → 128.0 SU/RPM），同步作用于动力网络、工程师护目镜与思索。
 */
@Mixin(value = BlockStressValues.class, remap = false)
public abstract class HighPressurePumpStressMixin {

    @ModifyReturnValue(
            method = "getImpact(Lnet/minecraft/world/level/block/Block;)D",
            at = @At("RETURN"),
            require = 1,
            allow = 1
    )
    private static double dimblend$scaleHighPressurePumpStressImpact(double original, Block block) {
        if (original <= 0.0D || block == null) {
            return original;
        }
        return HighPressurePumpTuning.scaleBlockStressImpact(BuiltInRegistries.BLOCK.getKey(block), original);
    }
}
