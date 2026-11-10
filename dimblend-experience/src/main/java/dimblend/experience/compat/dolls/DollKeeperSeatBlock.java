package dimblend.experience.compat.dolls;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Create's shape/colour/water state, with all sitting and dyeing entry points replaced. */
public final class DollKeeperSeatBlock extends SeatBlock implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape DOLL_SELECTION = Block.box(2, 8, 2, 14, 24, 14);

    public DollKeeperSeatBlock(Properties properties, DyeColor color) {
        super(properties, color);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DollKeeperSeatBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != DollKeeperSeats.BLOCK_ENTITY.get() ? null
                : (world, pos, current, entity) -> DollKeeperSeatBlockEntity.tick(world, pos, current,
                        (DollKeeperSeatBlockEntity) entity);
    }

    @Override
    public void updateEntityAfterFallOn(BlockGetter level, Entity entity) {
        // Do not call SeatBlock: falling mobs would otherwise be mounted automatically.
        entity.setDeltaMovement(entity.getDeltaMovement().multiply(1, 0, 1));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Make the visible doll clickable too. SeatBlock's separate collision shape remains unchanged.
        return Shapes.or(super.getShape(state, level, pos, context), DOLL_SELECTION);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return super.getShape(state, level, pos, CollisionContext.empty());
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return AllBlocks.SEATS.get(getColor()).asStack();
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // The event handles menu interaction, including shift-clicks and empty hands.
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
            BlockPos neighborPos, boolean moving) {
        if (level.getBlockEntity(pos) instanceof DollKeeperSeatBlockEntity keeper) {
            keeper.checkTicker(level);
        }
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(AllBlocks.SEATS.get(getColor()).asStack());
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof DollKeeperSeatBlockEntity keeper) {
            ItemStack doll = keeper.doll();
            if (!doll.isEmpty()) {
                drops.add(doll);
            }
        }
        return drops;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.getAbilities().instabuild
                && level.getBlockEntity(pos) instanceof DollKeeperSeatBlockEntity keeper) {
            Block.popResource(level, pos, keeper.takeDoll());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }
}
