package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.worldgen.PostProcessFluidFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Drops the {@code FluidState.tick} call in {@code LevelChunk.postProcessGeneration} for
 * enclosed source water that provably cannot spread. The sibling
 * {@code Block.updateFromNeighbourShapes} / {@code level.setBlock} branch is untouched: fluid
 * blocks skip it anyway, and every non-fluid post-processing mark must still be applied.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkPostProcessFluidMixin {
    @WrapOperation(
            method = "postProcessGeneration",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"
            ),
            require = 1,
            allow = 1
    )
    private void dimblend$skipEnclosedSourceWaterTick(
            FluidState fluidState,
            Level level,
            BlockPos pos,
            Operation<Void> original
    ) {
        if (PostProcessFluidFilter.canSkipPostProcessTick((LevelChunk) (Object) this, level, pos, fluidState)) {
            return;
        }
        original.call(fluidState, level, pos);
    }
}
