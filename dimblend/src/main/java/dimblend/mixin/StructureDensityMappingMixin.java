package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.worldgen.StructureNoiseSetup;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DensityFunctions.HolderHolder.class)
public abstract class StructureDensityMappingMixin {
    @WrapMethod(method = "mapAll", require = 1, allow = 1)
    private DensityFunction dimblend$reuseMappedStructureBranch(
            DensityFunction.Visitor visitor, Operation<DensityFunction> original
    ) {
        if (!StructureNoiseSetup.mappingActive(visitor)) {
            return original.call(visitor);
        }
        DensityFunction source = ((DensityFunctions.HolderHolder) (Object) this).function().value();
        return StructureNoiseSetup.mapReference(source, visitor, () -> original.call(visitor));
    }
}
