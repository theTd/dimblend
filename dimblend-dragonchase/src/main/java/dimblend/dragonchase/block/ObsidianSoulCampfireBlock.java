package dimblend.dragonchase.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The crystal car's pedestal: a soul campfire whose logs are crying obsidian.
 *
 * <p><b>Deliberately not {@code CampfireBlock}.</b> Vanilla's shape, light level and default state
 * are reused ({@code CampfireBlock#SHAPE}, {@code litBlockEmission(10)}, {@code LIT = true}), but
 * {@code CampfireBlock#entityInside} hurts every {@code LivingEntity} standing in it for two points
 * of fire damage. The pedestals sit on the deck of a car players board and fight on, so this block
 * overrides nothing: {@code Block#entityInside} does nothing, and no recipe, cooking block entity or
 * particle tick is carried over either. It is a decorated 7/16-high block with the campfire model.
 *
 * <p><b>Static model.</b> {@code FACING} and {@code LIT} exist so the vanilla campfire model and
 * blockstate rotations apply unchanged; the car never changes either value after placement. The
 * block is never waterlogged and never emits smoke, so those vanilla properties are absent.
 */
public class ObsidianSoulCampfireBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ObsidianSoulCampfireBlock> CODEC = simpleCodec(ObsidianSoulCampfireBlock::new);

    /** The campfire's lit flag: always true on a car, but part of the vanilla model's state space. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** Vanilla {@code CampfireBlock#SHAPE}: a 7/16-high block the player can stand on. */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 7.0, 16.0);

    public ObsidianSoulCampfireBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, Boolean.TRUE));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
