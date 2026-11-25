package dimblend.blocks.compat.rail;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.equipment.wrench.IWrenchableWithBracket;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 列车加压管道方块（{@code dimblend_blocks:train_pressured_tube}）。
 *
 * <p>形状语义借 {@link PipeBlock} 的 6 个连接布尔与 {@code shapeByIndex}，但<b>不是</b>
 * {@code SimpleWaterloggedBlock}：<b>没有 WATERLOGGED 属性</b>。这一点是硬要求——
 * Create 的 {@code FluidPropagator#isOpenEnd} 把 {@code canBeReplaced()} 与
 * {@code hasProperty(WATERLOGGED)} 当"开放流体口"，两者都必须为假，否则管道会被当成漏气口。</p>
 *
 * <p>连接只认两种邻居：本模组管道；{@code createfirefightingadd:high_pressure_pump}
 * 且泵的朝向轴与连接方向同轴。普通管道、机械泵、流体罐、物品排放口一律不连。</p>
 *
 * <p>两个派生属性让 blockstate 走纯 multipart（不需要自定义 baked model）：</p>
 * <ul>
 *   <li>{@code single_end}：本地连接数恰好 1（跨车厢锚点的资格，逻辑用）；</li>
 *   <li>{@code casing}：渲染中心方块体——单端接头或折角/多通时是 {@code [3,3,3]..[13,13,13]} 的盲管外壳，
 *       直通（两个对向臂）与空管时只画核心管体。</li>
 * </ul>
 */
public class TrainPressuredTubeBlock extends PipeBlock implements EntityBlock, IWrenchableWithBracket {

    public static final MapCodec<TrainPressuredTubeBlock> CODEC = simpleCodec(TrainPressuredTubeBlock::new);

    public static final BooleanProperty SINGLE_END = BooleanProperty.create("single_end");
    public static final BooleanProperty CASING = BooleanProperty.create("casing");

    private static final Direction[] DIRECTIONS = Direction.values();

    public TrainPressuredTubeBlock(Properties properties) {
        super(0.25f, properties);
        BlockState state = stateDefinition.any();
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), false);
        }
        registerDefaultState(state.setValue(SINGLE_END, false).setValue(CASING, false));
    }

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, SINGLE_END, CASING);
    }

    // ------------------------------------------------------------------ 连接判定

    public static boolean isConnected(BlockState state, Direction direction) {
        return state.getValue(PROPERTY_BY_DIRECTION.get(direction));
    }

    /** 邻方块是否算"连上"：本模组管道，或朝该方向同轴的高压水泵。 */
    public static boolean connectsToState(@Nullable BlockState neighborState, Direction direction) {
        if (neighborState == null) {
            return false;
        }
        if (TrainTubes.isTube(neighborState)) {
            return true;
        }
        return TrainTubes.isHighPressurePump(neighborState, direction.getAxis());
    }

    /** 由 6 个连接布尔推 {@code single_end} / {@code casing}。 */
    public static BlockState derive(BlockState state) {
        int count = 0;
        Direction.Axis sharedAxis = null;
        boolean axisMixed = false;
        for (Direction direction : DIRECTIONS) {
            if (!isConnected(state, direction)) {
                continue;
            }
            count++;
            if (sharedAxis == null) {
                sharedAxis = direction.getAxis();
            } else if (sharedAxis != direction.getAxis()) {
                axisMixed = true;
            }
        }
        boolean straightThrough = count == 2 && !axisMixed;
        boolean casing = count == 1 || (count >= 2 && !straightThrough);
        return state.setValue(SINGLE_END, count == 1).setValue(CASING, casing);
    }

    private static boolean sameConnections(BlockState first, BlockState second) {
        for (Direction direction : DIRECTIONS) {
            if (isConnected(first, direction) != isConnected(second, direction)) {
                return false;
            }
        }
        return first.getValue(SINGLE_END) == second.getValue(SINGLE_END)
                && first.getValue(CASING) == second.getValue(CASING);
    }

    // ------------------------------------------------------------------ 放置与形状刷新

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockGetter level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = defaultBlockState();
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction),
                    connectsToState(level.getBlockState(pos.relative(direction)), direction));
        }
        return derive(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        boolean previous = state.getValue(PROPERTY_BY_DIRECTION.get(direction));
        boolean current = connectsToState(neighborState, direction);
        BlockState next = state.setValue(PROPERTY_BY_DIRECTION.get(direction), current);
        if (previous != current && level instanceof ServerLevel serverLevel) {
            TrainTubeNetworkManager.markPending(serverLevel, pos);
        }
        return derive(next);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
            boolean isMoving) {
        if (!level.isClientSide) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!oldState.is(state.getBlock()) && level instanceof ServerLevel serverLevel) {
            TrainTubeNetworkManager.markPending(serverLevel, pos);
        }
    }

    /**
     * 排程 tick 只做两件事：把派生属性（{@code single_end}/{@code casing}）对齐到当前连接；
     * 真检测出连接位翻转时（理论上 {@code updateShape} 已处理，这里是兜底）再通知一次拓扑变更。
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState recomputed = recompute(level, pos);
        if (recomputed.equals(state)) {
            return;
        }
        level.setBlock(pos, recomputed, 3);
        if (!sameConnections(state, recomputed)) {
            TrainTubeNetworkManager.markPending(level, pos);
        }
    }

    private BlockState recompute(BlockGetter level, BlockPos pos) {
        BlockState state = defaultBlockState();
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction),
                    connectsToState(level.getBlockState(pos.relative(direction)), direction));
        }
        return derive(state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (level instanceof ServerLevel serverLevel) {
                TrainTubeNetworkManager.markPending(serverLevel, pos);
                UUID subLevel = TrainTubeSable.subLevelId(level, pos);
                if (subLevel != null) {
                    TrainTubeNetworkManager.removeLinksAt(serverLevel, subLevel, pos);
                }
            }
            if (!level.isClientSide) {
                removeBracket(level, pos, true).ifPresent(stack -> Block.popResource(level, pos, stack));
            }
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrainPressuredTubeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof TrainPressuredTubeBlockEntity tube) {
                tube.tick();
            }
        };
    }

    // ------------------------------------------------------------------ 扳手

    @Override
    public Optional<ItemStack> removeBracket(BlockGetter level, BlockPos pos, boolean inOnReplacedContext) {
        BracketedBlockEntityBehaviour behaviour =
                BlockEntityBehaviour.get(level, pos, BracketedBlockEntityBehaviour.TYPE);
        if (behaviour == null) {
            return Optional.empty();
        }
        BlockState bracket = behaviour.removeBracket(inOnReplacedContext);
        if (bracket == null) {
            return Optional.empty();
        }
        return Optional.of(new ItemStack(bracket.getBlock()));
    }

    /**
     * 非潜行扳手：先卸支架（Create 的通用语义），没卸到支架且本块是端点时进入跨车厢串联流程。
     * 潜行拆管走 {@code IWrenchable} 默认实现（掉落 + 破坏），{@link #onRemove} 会顺带清理连接。
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        if (tryRemoveBracket(context)) {
            return InteractionResult.SUCCESS;
        }
        if (context.getLevel() instanceof ServerLevel serverLevel) {
            TrainTubeLinkInteraction.onWrench(serverLevel, context.getClickedPos(), context.getPlayer());
        }
        return InteractionResult.SUCCESS;
    }
}
