package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.worldgen.StructureNoiseSetup;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NoiseChunk.class)
public abstract class StructureNoiseRouterMixin {
    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"),
            require = 1,
            allow = 1
    )
    private NoiseRouter dimblend$mapSharedStructureNodes(
            NoiseRouter router, DensityFunction.Visitor visitor, Operation<NoiseRouter> original
    ) {
        return StructureNoiseSetup.mapNodes(visitor, () -> original.call(router, visitor));
    }

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"),
            require = 1,
            allow = 1
    )
    private DensityFunction dimblend$mapSharedStructureMaterial(
            DensityFunction function, DensityFunction.Visitor visitor, Operation<DensityFunction> original
    ) {
        return StructureNoiseSetup.mapNodes(visitor, () -> original.call(function, visitor));
    }
}
