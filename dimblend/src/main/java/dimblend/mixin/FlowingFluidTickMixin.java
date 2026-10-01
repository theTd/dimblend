package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.fluid.NetherLavaFlow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A scheduled lava tick runs {@code getNewLiquid}, {@code getSpread} and
 * {@code getSpreadDelay}, which read the lava flow constants through
 * {@link LavaFluidUltrawarmMixin} with no position. Push the column for the whole tick.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidTickMixin {
    @WrapMethod(method = "tick")
    private void dimblend$lavaFlowColumn(Level level, BlockPos pos, FluidState state, Operation<Void> original) {
        if (!((Object) this instanceof LavaFluid)) {
            original.call(level, pos, state);
            return;
        }
        Integer outer = NetherLavaFlow.enter(pos);
        try {
            original.call(level, pos, state);
        } finally {
            NetherLavaFlow.exit(outer);
        }
    }
}
