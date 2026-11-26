package dimblend.blocks.compat.create.funnel;

import com.simibubi.create.AllShapes;
import com.simibubi.create.content.logistics.funnel.AbstractHorizontalFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 贴在传送带上的卸料漏斗（{@code dimblend_blocks:dump_belt_funnel}）。
 *
 * <p>没有独立物品形态：战利品表与 {@link #getCloneItemStack} 都给
 * {@code dimblend_blocks:dump_funnel}（与原版黄铜传送带漏斗一致）。</p>
 *
 * <p>形态只走 {@code RETRACTED / EXTENDED / PUSHING}：<b>永不允许 {@code PULLING}</b>
 * ——卸料漏斗只会把物品推到传送带上。放置时按
 * {@link BeltFunnelBlock#getShapeForPosition}（{@code extracting = true}）算形态，
 * 因此传送带与朝向同轴时是 {@code RETRACTED}，异轴时是 {@code PUSHING}。</p>
 *
 * <p>只能声明继承 {@code AbstractHorizontalFunnelBlock}：Create 的
 * {@code BeltFunnelBlock} 构造器要 {@code registrate BlockEntry}，第三方方块无法复用；
 * 代价是 {@code FunnelBlockEntity#determineCurrentMode()} 认不出本方块（它只
 * {@code instanceof BeltFunnelBlock}），所以推送逻辑在
 * {@link DumpFunnelBlockEntity#tick()} 里自行实现。</p>
 */
public class DumpBeltFunnelBlock extends AbstractHorizontalFunnelBlock {

    /** 形态属性：复用 Create 自己的 {@code BeltFunnelBlock.Shape} 枚举与序列化名。 */
    public static final EnumProperty<BeltFunnelBlock.Shape> SHAPE =
            EnumProperty.create("shape", BeltFunnelBlock.Shape.class);

    public DumpBeltFunnelBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(SHAPE, BeltFunnelBlock.Shape.RETRACTED));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(SHAPE));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        Direction facing = context.getClickedFace().getAxis().isHorizontal()
                ? context.getClickedFace()
                : context.getHorizontalDirection();
        state = state.setValue(HORIZONTAL_FACING, facing);
        return state.setValue(SHAPE, shapeForPosition(context.getLevel(), context.getClickedPos(), facing));
    }

    /** 放置用形态：卸料漏斗只推不抽，{@code extracting = true} → 只会得到 PUSHING / RETRACTED。 */
    private static BeltFunnelBlock.Shape shapeForPosition(BlockGetter level, BlockPos pos, Direction facing) {
        return shapeForPosition(level, pos, facing, true);
    }

    private static BeltFunnelBlock.Shape shapeForPosition(BlockGetter level, BlockPos pos, Direction facing,
            boolean extracting) {
        return BeltFunnelBlock.getShapeForPosition(level, pos, facing, extracting);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(HORIZONTAL_FACING);
        return switch (state.getValue(SHAPE)) {
            case RETRACTED -> AllShapes.BELT_FUNNEL_RETRACTED.get(facing);
            case EXTENDED -> AllShapes.BELT_FUNNEL_EXTENDED.get(facing);
            default -> AllShapes.BELT_FUNNEL_PERPENDICULAR.get(facing);
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof ItemEntity
                && state.getValue(SHAPE) == BeltFunnelBlock.Shape.PUSHING) {
            return AllShapes.FUNNEL_COLLISION.get(state.getValue(HORIZONTAL_FACING));
        }
        return getShape(state, level, pos, context);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BeltFunnelBlock.Shape current = state.getValue(SHAPE);
        BeltFunnelBlock.Shape next = switch (current) {
            case RETRACTED -> BeltFunnelBlock.Shape.EXTENDED;
            case EXTENDED -> BeltFunnelBlock.Shape.RETRACTED;
            // 垂直形态（传送带与朝向异轴）没有"推/抽"这一对可切：原版是 PUSHING↔PULLING，
            // 我们永不进入 PULLING，所以这里保持原样，避免落回一个立刻被 updateShape 改掉的中间态。
            case PUSHING, PULLING -> current;
        };
        if (next != current) {
            level.setBlockAndUpdate(context.getClickedPos(), state.setValue(SHAPE, next));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 传送带没了/不对齐：退回立式卸料漏斗（保持朝向、通电与含水状态）。
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        updateWater(level, state, pos);
        if (!BeltFunnelBlock.isOnValidBelt(state, (LevelReader) level, pos)) {
            BlockState parentState = ProperWaterloggedBlock.withWater(level,
                    DumpFunnels.DUMP_FUNNEL_BLOCK.get().defaultBlockState(), pos);
            if (state.getOptionalValue(POWERED).orElse(Boolean.FALSE)) {
                parentState = parentState.setValue(POWERED, Boolean.TRUE);
            }
            return parentState.setValue(FunnelBlock.EXTRACTING, Boolean.TRUE)
                    .setValue(FunnelBlock.FACING, state.getValue(HORIZONTAL_FACING));
        }
        BeltFunnelBlock.Shape current = state.getValue(SHAPE);
        DumpFunnelLayout.Shape currentLayout = DumpFunnelLayout.Shape.of(current);
        DumpFunnelLayout.Shape computed = DumpFunnelLayout.normalizeComputedShape(
                DumpFunnelLayout.Shape.of(shapeForPosition((BlockGetter) level, pos,
                        state.getValue(HORIZONTAL_FACING), current == BeltFunnelBlock.Shape.PUSHING)),
                currentLayout);
        DumpFunnelLayout.Shape resolved = DumpFunnelLayout.resolveNeighborShape(computed, currentLayout);
        return resolved == currentLayout ? state : state.setValue(SHAPE, resolved.toCreate());
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
            Player player) {
        return new ItemStack(DumpFunnels.DUMP_FUNNEL_ITEM.get());
    }

    /** 该形态从不塞入物品（无 {@code useItemOn} / {@code entityInside} 插入路径）。 */
    public boolean canInsertIntoFunnel(BlockState state) {
        return false;
    }

    @Override
    public Class<FunnelBlockEntity> getBlockEntityClass() {
        return FunnelBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FunnelBlockEntity> getBlockEntityType() {
        return DumpFunnels.DUMP_FUNNEL_BE.get();
    }
}
