package dimblend.mixin.twilight;

import dimblend.worldgen.YShiftScope;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import twilightforest.world.components.structures.type.LabyrinthStructure;

@Mixin(value = LabyrinthStructure.class, remap = false)
public abstract class LabyrinthStructureMixin {
    @Redirect(
            method = "getStructureTerraformer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunctions;yClampedGradient(IIDD)Lnet/minecraft/world/level/levelgen/DensityFunction;",
                    remap = true
            )
    )
    private DensityFunction dimblend$shiftGradient(int fromY, int toY, double fromValue, double toValue) {
        int offset = YShiftScope.current();
        return DensityFunctions.yClampedGradient(fromY + offset, toY + offset, fromValue, toValue);
    }
}
