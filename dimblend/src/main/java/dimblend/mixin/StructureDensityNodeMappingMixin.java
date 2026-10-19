package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.worldgen.StructureNoiseSetup;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;

/** Shared arithmetic subtrees also occur after registry holders have been unwrapped. */
@Mixin(targets = {
        "net.minecraft.world.level.levelgen.DensityFunctions$Ap2",
        "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd",
        "net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice"
})
public abstract class StructureDensityNodeMappingMixin {
    @WrapMethod(method = "mapAll", require = 1, allow = 1)
    private DensityFunction dimblend$reuseMappedStructureNode(
            DensityFunction.Visitor visitor, Operation<DensityFunction> original
    ) {
        if (!StructureNoiseSetup.mappingActive(visitor)) {
            return original.call(visitor);
        }
        return StructureNoiseSetup.mapReference((DensityFunction) (Object) this, visitor,
                () -> original.call(visitor));
    }
}
