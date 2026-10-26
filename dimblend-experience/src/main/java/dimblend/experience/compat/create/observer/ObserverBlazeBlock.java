package dimblend.experience.compat.create.observer;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The observer blaze: a stock keeper blaze and a floating spyglass, with no cage. It accepts no
 * fuel and provides no heat (it has no heat property and is in no Create heater registration), and it emits
 * weak redstone power on all sides while a searchlight marks lava near it.
 */
public final class ObserverBlazeBlock extends Block implements EntityBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public ObserverBlazeBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    // --- Redstone: weak power on all six sides, like a redstone block. ---

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    // --- Shape and collision: Create's heater block, including its train-friendly special collision shape. ---

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return AllShapes.HEATER_BLOCK_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return context == CollisionContext.empty()
                ? AllShapes.HEATER_BLOCK_SPECIAL_COLLISION_SHAPE : AllShapes.HEATER_BLOCK_SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    // --- Block entity ---

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ObserverBlazeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (tickingLevel, pos, tickingState, blockEntity) -> {
            if (blockEntity instanceof ObserverBlazeBlockEntity observer) {
                observer.tickObserver(tickingLevel, pos, tickingState);
            }
        };
    }

    /** Middle-click and the Create schematicannon see the burner, not this block. */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return AllBlocks.BLAZE_BURNER.asStack();
    }

    /** A basin directly above caches the heat below it; Create refreshes it on placement, and so do we. */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && level.getBlockEntity(pos.above()) instanceof BasinBlockEntity basin) {
            basin.notifyChangeOfContents();
        }
    }

    /**
     * Ambient flame while powered. {@code animateTick} is a sampled hook (the client walks ~1334 random block
     * positions per tick, so one block is visited a few percent of the time), which is why the block entity's
     * client tick emits the continuous stream; this adds the Create/vanilla-style puff on sampled ticks.
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(POWERED)) {
            emitFlame(level, pos, random, 3);
        }
    }

    /** A few flames above the blaze, in the style of Create's burner particles. */
    static void emitFlame(Level level, BlockPos pos, RandomSource random, int count) {
        for (int i = 0; i < count; i++) {
            double x = pos.getX() + 0.2 + random.nextDouble() * 0.6;
            double y = pos.getY() + 0.95 + random.nextDouble() * 0.3;
            double z = pos.getZ() + 0.2 + random.nextDouble() * 0.6;
            level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.005, 0.0);
        }
    }
}
