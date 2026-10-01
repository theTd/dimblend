package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.fluid.NetherLavaFlow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The first lava tick is scheduled from these three block hooks with
 * {@code fluid.getTickDelay(level)}; push the column so the nether-speed delay applies
 * to it too. See {@link NetherLavaFlow}.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockLavaFlowMixin {
    @WrapMethod(method = "onPlace")
    private void dimblend$lavaFlowColumnOnPlace(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState oldState,
            boolean movedByPiston,
            Operation<Void> original
    ) {
        Integer outer = NetherLavaFlow.enter(pos);
        try {
            original.call(state, level, pos, oldState, movedByPiston);
        } finally {
            NetherLavaFlow.exit(outer);
        }
    }

    @WrapMethod(method = "updateShape")
    private BlockState dimblend$lavaFlowColumnUpdateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos,
            Operation<BlockState> original
    ) {
        Integer outer = NetherLavaFlow.enter(pos);
        try {
            return original.call(state, direction, neighborState, level, pos, neighborPos);
        } finally {
            NetherLavaFlow.exit(outer);
        }
    }

    @WrapMethod(method = "neighborChanged")
    private void dimblend$lavaFlowColumnNeighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston,
            Operation<Void> original
    ) {
        Integer outer = NetherLavaFlow.enter(pos);
        try {
            original.call(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        } finally {
            NetherLavaFlow.exit(outer);
        }
    }
}
