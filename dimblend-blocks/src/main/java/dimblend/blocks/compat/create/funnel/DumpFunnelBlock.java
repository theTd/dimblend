package dimblend.blocks.compat.create.funnel;

import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 立式卸料漏斗（{@code dimblend_blocks:dump_funnel}）。
 *
 * <p>与黄铜漏斗的差异：{@code EXTRACTING} 恒 {@code true}（只做"抽出来扔在地上"这一半），
 * 扳手不能翻转抽取/收集方向，任何物品都塞不进去（{@code canInsertIntoFunnel} 恒
 * {@code false}，连带 {@code entityInside} 的吸取与手持塞入都失效）。</p>
 *
 * <p>贴着传送带放置时会转成 {@link DumpBeltFunnelBlock}（父类 {@code FunnelItem} 的
 * 放置逻辑与 {@link #updateShape} 都走 {@link #getEquivalentBeltFunnel}），
 * 拆掉传送带再转回立式。</p>
 */
public class DumpFunnelBlock extends FunnelBlock {

    public DumpFunnelBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(EXTRACTING, Boolean.TRUE));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(EXTRACTING, Boolean.TRUE);
    }

    /**
     * 等价传送带漏斗状态。必须像 Create 的 {@code BrassFunnelBlock#getEquivalentBeltFunnel}
     * 那样把<b>朝向与通电</b>抄过去：直接给 {@code defaultBlockState()} 会让任何"先放漏斗、
     * 后形成/放置传送带"（以及 {@link #updateShape} 的换算）都得到朝向 NORTH、
     * 未通电的传送带漏斗——模型整体转 90°，与皮带条走向错开，过滤槽也落到错误的面。
     */
    @Override
    public BlockState getEquivalentBeltFunnel(BlockGetter world, BlockPos pos, BlockState state) {
        Direction facing = AbstractFunnelBlock.getFunnelFacing(state);
        if (facing == null || !facing.getAxis().isHorizontal()) {
            // 调用方（FunnelItem 放置路径与本类 updateShape）只在水平态换算；立式态没有等价形态
            return DumpFunnels.DUMP_BELT_FUNNEL_BLOCK.get().defaultBlockState();
        }
        return DumpFunnels.DUMP_BELT_FUNNEL_BLOCK.get().defaultBlockState()
                .setValue(DumpBeltFunnelBlock.HORIZONTAL_FACING, facing)
                .setValue(POWERED, state.getValue(POWERED));
    }

    /**
     * 只做转化，不翻抽取方向：扳手对卸料漏斗是"无操作"（但保留成功反馈，免得玩家以为没响应）。
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean canInsertIntoFunnel(BlockState state) {
        return false;
    }

    /**
     * 原版 {@code FunnelBlock#updateShape} 会在等价传送带漏斗状态上写 Create 自家
     * {@code BeltFunnelBlock.SHAPE}——那是另一个方块的状态属性，直接复用会抛
     * {@code IllegalArgumentException}。这里改写成本方块的 {@link DumpBeltFunnelBlock#SHAPE}。
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        updateWater(level, state, pos);
        if (AbstractFunnelBlock.getFunnelFacing(state).getAxis().isVertical() || direction != Direction.DOWN) {
            return state;
        }
        BlockState equivalent = ProperWaterloggedBlock.withWater(level,
                getEquivalentBeltFunnel(null, null, state), pos);
        if (BeltFunnelBlock.isOnValidBelt(equivalent, (LevelReader) level, pos)) {
            return equivalent.setValue(DumpBeltFunnelBlock.SHAPE,
                    BeltFunnelBlock.getShapeForPosition((BlockGetter) level, pos,
                            AbstractFunnelBlock.getFunnelFacing(state), true));
        }
        return state;
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
