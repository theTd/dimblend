package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.BandSeam;
import dimblend.worldgen.RotatingChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * W2 fluid barrier: a flowing fluid may never take a one-block horizontal step across a seam
 * column ({@code X = slabX}) in the rotating dimension.
 *
 * <p>The worldgen shell already turns water/lava inside the corridor into stained glass
 * ({@link dimblend.worldgen.OakTrackCorridor}), but a player or a drill can open that shell.
 * Without the removed {@code null_block} wall, a breach would let an ocean band drain into the
 * next band. This is the runtime backstop: the same {@link BandSeam} predicate the walking guard
 * and the Sable sweep use, evaluated once per candidate spread.
 *
 * <p>Only the server level is checked: client fluid prediction runs on {@code ClientLevel},
 * where the chunk generator (and therefore the band layout) does not exist.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidSeamMixin {

    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void dimblend$blockAcrossSeam(
            BlockGetter level,
            BlockPos fromPos,
            BlockState fromState,
            Direction direction,
            BlockPos toPos,
            BlockState toState,
            FluidState toFluid,
            Fluid incoming,
            CallbackInfoReturnable<Boolean> cir) {
        if (!BandSeam.isFramelessSeam()) {
            return;
        }
        if (!(level instanceof ServerLevel server)
                || server.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        if (!(server.getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return;
        }
        if (BandSeam.crossesSeamX(layout, rotating.bandSize(), fromPos.getX(), toPos.getX())) {
            cir.setReturnValue(false);
        }
    }
}
