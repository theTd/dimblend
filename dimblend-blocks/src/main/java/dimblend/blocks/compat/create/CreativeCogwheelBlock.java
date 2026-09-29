package dimblend.blocks.compat.create;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 创造模式齿轮（仅小号，不做大齿轮）：行为与 create:cogwheel 一致，差异与
 * {@link CreativeShaftBlock} 同口径（自有 BE、扳手不能拆、模式分派：创造旋转/生存互转）。
 *
 * <p>生存扳手普通右键：有支架先拆支架，否则变回 {@link CreativeShaftBlock}
 * （同轴同含水，{@code switchToBlockState} 保 BE/转速/支架）；潜行右键：
 * 创造=原版拆卸（不掉落），生存=无操作。</p>
 *
 * <p>useItemOn 不覆写：上游 CogWheelBlock 的实现就是「潜行/无权限挡掉 → tryEncase
 * → PASS」，没有轴那样的横梁套壳分支，也没有连放 helper，正是本板块要的语义。</p>
 *
 * <p>getStateForPlacement 保留 super 的轴向推断（潜行对齐点击面/吸附相邻小齿轮轴），
 * 仅前置创造门。</p>
 */
public class CreativeCogwheelBlock extends CogWheelBlock {

    private final Supplier<? extends BlockEntityType<? extends KineticBlockEntity>> blockEntityType;

    /** 上游构造器是 protected 且以 boolean 区分大小齿轮：本板块固定小号。 */
    public CreativeCogwheelBlock(Properties properties,
            Supplier<? extends BlockEntityType<? extends KineticBlockEntity>> blockEntityType) {
        super(false, properties);
        this.blockEntityType = blockEntityType;
    }

    @Override
    public BlockEntityType<? extends KineticBlockEntity> getBlockEntityType() {
        return blockEntityType.get();
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        // 创造：恢复 Create 原版扳手语义（先拆支架，否则旋转轴），同
        // {@link CreativeShaftBlock}（2026-09-29 拍板）
        if (player != null && player.isCreative()) {
            return super.onWrenched(state, context);
        }
        // 生存：不能调方向不能拆，只能互转——变回 {@link CreativeShaftBlock}
        if (tryRemoveBracket(context)) {
            return InteractionResult.SUCCESS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState swapped = CreativeKinetics.CREATIVE_SHAFT.get().defaultBlockState()
                .setValue(AXIS, state.getValue(AXIS))
                .setValue(WATERLOGGED, state.getValue(WATERLOGGED));
        if (!swapped.canSurvive(level, pos)) {
            return InteractionResult.PASS;
        }
        KineticBlockEntity.switchToBlockState(level, pos, swapped);
        if (level.getBlockState(pos) != state) {
            IWrenchable.playRotateSound(level, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        // 同 {@link CreativeShaftBlock}：创造=原版潜行拆卸（不掉落），生存=禁拆
        if (player != null && player.isCreative()) {
            return super.onSneakWrenched(state, context);
        }
        return InteractionResult.PASS;
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 方块级保险：覆盖常规 BlockItem 放置。注意齿轮物品的对角/嵌入 helper
        // 连放路径（placeInWorld）不走这里，由物品侧 onItemUseFirst 创造门与
        // EntityPlaceEvent 守卫双重封堵。
        Player player = context.getPlayer();
        if (player == null || !player.isCreative()) {
            return null;
        }
        return super.getStateForPlacement(context);
    }
}
