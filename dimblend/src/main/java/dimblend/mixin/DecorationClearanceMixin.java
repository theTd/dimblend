package dimblend.mixin;

import dimblend.worldgen.YShiftedStructureElevation;
import java.util.OptionalInt;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.world.components.structures.util.DecorationClearance;

@Mixin(value = DecorationClearance.class, remap = false)
public interface DecorationClearanceMixin {
    @Inject(method = "adjustForTerrain", at = @At("HEAD"), cancellable = true)
    default void dimblend$unclampLiftedSurface(
            Structure.GenerationContext context,
            int x,
            int z,
            CallbackInfoReturnable<Integer> cir
    ) {
        OptionalInt lifted = YShiftedStructureElevation.unclampIfShifted(
                context.chunkGenerator(),
                ((DecorationClearance) this).shouldAdjustToTerrain(),
                x,
                z,
                context.heightAccessor(),
                context.randomState());
        if (lifted.isPresent()) {
            cir.setReturnValue(lifted.getAsInt());
        }
    }
}
