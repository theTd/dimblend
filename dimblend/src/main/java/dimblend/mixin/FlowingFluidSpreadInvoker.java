package dimblend.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(FlowingFluid.class)
public interface FlowingFluidSpreadInvoker {
    @Invoker("canSpreadTo")
    boolean dimblend$canSpreadTo(BlockGetter level, BlockPos from, BlockState fromState,
            Direction direction, BlockPos to, BlockState toState, FluidState toFluid, Fluid incoming);
}
