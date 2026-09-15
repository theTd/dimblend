package dimblend.mixin;

import dimblend.worldgen.WorldGenerationContextExtension;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lifts absolute world Y (ores, buried jigsaws without a heightmap) by the
 * twilight terrain offset. Heightmap-projected jigsaw {@code start_height} is an
 * offset from the surface, not a world Y — {@link JigsawStructureMixin} clears
 * this shift while that value is sampled.
 */
@Mixin(VerticalAnchor.Absolute.class)
public abstract class VerticalAnchorAbsoluteMixin {
    @Inject(method = "resolveY", at = @At("RETURN"), cancellable = true)
    private void dimblend$shiftAbsolute(WorldGenerationContext context, CallbackInfoReturnable<Integer> cir) {
        if (context instanceof CarvingContext) {
            return;
        }
        int offset = ((WorldGenerationContextExtension) context).dimblend$absoluteOffset();
        if (offset != 0) {
            cir.setReturnValue(cir.getReturnValueI() + offset);
        }
    }
}
