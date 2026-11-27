package dimblend.blocks.compat.rail;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 列车加压管道的方块实体。
 *
 * <p>只有两件事：挂 Create 的支架行为；按面决定是否暴露流体接口。支架行为带"仅直管"谓词
 * （{@link TrainTubeBracketRules#canHaveBracket}），非直管形状不允许再放支架。</p>
 *
 * <p>暴露规则是整套"只让高压水泵进出"设计的关键：只有当该面<b>连接位为真</b>且邻方块是
 * 朝同轴的高压水泵时才返回 {@link IFluidHandler}，其余情况一律 {@code null}。Create 的
 * {@code FluidPropagator} 因此把管道当成 closed end，普通机械泵既不会把它当管道，
 * 也不会往里面灌液或喷粒子。</p>
 */
public class TrainPressuredTubeBlockEntity extends SmartBlockEntity {

    public TrainPressuredTubeBlockEntity(BlockPos pos, BlockState state) {
        super(TrainTubes.TUBE_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new BracketedBlockEntityBehaviour(this, state -> TrainTubeBracketRules.canHaveBracket(state)));
    }

    /** 供能力注册回调使用：{@code side == null} 永远不给接口（中心访问一律视为无能力）。 */
    @Nullable
    public IFluidHandler fluidHandlerFor(@Nullable Direction side) {
        if (side == null) {
            return null;
        }
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return null;
        }
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof TrainPressuredTubeBlock)) {
            return null;
        }
        if (!TrainPressuredTubeBlock.isConnected(state, side)) {
            return null;
        }
        BlockState neighbor = TrainTubeNetworkManager.stateOrNull(level, getBlockPos().relative(side));
        if (!TrainTubes.isHighPressurePump(neighbor, side.getAxis())) {
            return null;
        }
        return new TrainTubeFluidHandler(serverLevel, getBlockPos());
    }
}
