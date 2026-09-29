package dimblend.blocks.compat.copycats;

import com.copycatsplus.copycats.content.copycat.slab.CopycatSlabBlock;
import com.copycatsplus.copycats.foundation.copycat.multistate.MaterialItemStorage;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import com.copycatsplus.copycats.utility.InteractionUtils;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import dimblend.blocks.api.CreativeCopycatHiding;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * C1 创造模式伪装半砖：行为与 Copycats+ 伪装半砖（上半/下半/双砖）一致，差异：
 * <ul>
 * <li>BE 用本模组自己的多状态类型（只绑定本方块，绕开 Copycats 的固定白名单）</li>
 * <li>扳手只能撕掉贴的材质（普通/潜行都一样），本体永不可拆</li>
 * <li>蓝图取材返回 NONE，避免 schematicannon 索要无法获取的物品</li>
 * <li>不可破坏/防爆/仅创造可放置由方块 Properties 与 {@link CreativeOnlyBlockItem} 承担</li>
 * <li>C5 隐藏：未贴材质时创造玩家空手右键隐藏、扳手右键恢复，语义同
 * {@link CreativeCopycatPanelBlock}；有材质时保留上游空手 toggleCT 行为</li>
 * </ul>
 */
public class CreativeCopycatSlabBlock extends CopycatSlabBlock {

    /**
     * 创造物品侧向连放：原版 helper 的物品谓词只认原版半砖
     * （{@code CCBlocks.COPYCAT_SLAB::isIn}），手持创造半砖右键无反应；
     * 此 helper 接受创造半砖，偏移逻辑与原版一致。
     */
    private static final int CREATIVE_PLACEMENT_HELPER_ID =
            PlacementHelpers.register(new CreativeSlabPlacementHelper());

    /** 洁净变体（C4）与原变体共享本类，BE 类型由注册侧注入。 */
    private final Supplier<? extends BlockEntityType<? extends MultiStateCopycatBlockEntity>> blockEntityType;

    public CreativeCopycatSlabBlock(Properties properties,
            Supplier<? extends BlockEntityType<? extends MultiStateCopycatBlockEntity>> blockEntityType) {
        super(properties);
        this.blockEntityType = blockEntityType;
    }

    @Override
    public BlockEntityType<? extends MultiStateCopycatBlockEntity> getBlockEntityType() {
        return blockEntityType.get();
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        // 只撕材质：不调 super（潜行扳手的原版语义是拆掉本体）
        return onWrenched(state, context);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        // 隐藏优先：扳手右键恢复正常（仅创造玩家），其余维持上游撕材质语义
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (player != null && player.isCreative() && CreativeCopycatHiding.isHidden(level, pos)) {
            CreativeCopycatHiding.setHidden(level, pos, false);
            IWrenchable.playRotateSound(level, pos);
            return InteractionResult.SUCCESS;
        }
        return super.onWrenched(state, context);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        // 上游（Copycats+ 多状态块）的 useWithoutItem 是空手 toggleCT；未贴材质时
        // CT 无从谈起，隐藏手势优先；有材质时保留 toggleCT。
        // 隐藏块对空手交互完全惰性（先短路，不落到上游 toggleCT 改写 BE 状态）。
        if (CreativeCopycatHiding.isHidden(level, pos)) {
            return InteractionResult.PASS;
        }
        if (player != null && player.isCreative() && player.getMainHandItem().isEmpty()
                && hasNoMaterial(level, pos)) {
            CreativeCopycatHiding.setHidden(level, pos, true);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.useWithoutItem(state, level, pos, player, hitResult);
    }

    /** 全部件均未贴自定义材质（multistate 存储逐部件判定）。 */
    private static boolean hasNoMaterial(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MultiStateCopycatBlockEntity copycat)) {
            return false;
        }
        MaterialItemStorage storage = copycat.getMaterialItemStorage();
        return storage.getAllProperties().stream().noneMatch(storage::hasCustomMaterial);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 方块级保险：覆盖常规 BlockItem 放置。注意 helper 连放路径
        // （PlacementOffset.placeInWorld）不走这里，由 useItemOn 内创造门把守。
        Player player = context.getPlayer();
        if (player == null || !player.isCreative()) {
            return null;
        }
        return super.getStateForPlacement(context);
    }

    @Override
    public ItemRequirement getRequiredItems(BlockState state, BlockEntity blockEntity) {
        return ItemRequirement.NONE;
    }

    @Override
    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        // 隐藏期间一切物品交互穿透（不贴材质、不连放），同 {@link CreativeCopycatPanelBlock}
        if (CreativeCopycatHiding.isHidden(level, pos)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return InteractionUtils.sequentialItem(
                // 生存旁路封堵：helper 连放（placeInWorld）不走 canPlace/getStateForPlacement，
                // 非创造一律放行到扳手/材质逻辑。
                () -> player != null && player.isCreative()
                        ? InteractionUtils.usePlacementHelper(CREATIVE_PLACEMENT_HELPER_ID, stack, state, level,
                                pos, player, hand, hitResult)
                        : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,
                () -> super.useItemOn(stack, state, level, pos, player, hand, hitResult));
    }

    @ParametersAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    private static class CreativeSlabPlacementHelper implements IPlacementHelper {
        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof CreativeCopycatSlabBlock;
        }

        @Override
        public Predicate<BlockState> getStatePredicate() {
            return state -> state.getBlock() instanceof CopycatSlabBlock;
        }

        @Override
        public PlacementOffset getOffset(Player player, Level world, BlockState state, BlockPos pos,
                BlockHitResult ray) {
            List<Direction> directions = IPlacementHelper.orderedByDistanceExceptAxis(pos, ray.getLocation(),
                    state.getValue(AXIS),
                    dir -> world.getBlockState(pos.relative(dir))
                            .canBeReplaced());

            if (directions.isEmpty())
                return PlacementOffset.fail();
            else {
                if (state.getValue(SLAB_TYPE).equals(SlabType.DOUBLE)) {
                    return PlacementOffset.success(pos.relative(directions.get(0)),
                            s -> s.setValue(AXIS, state.getValue(AXIS)).setValue(SLAB_TYPE, SlabType.BOTTOM));
                } else {
                    return PlacementOffset.success(pos.relative(directions.get(0)),
                            s -> s.setValue(AXIS, state.getValue(AXIS)).setValue(SLAB_TYPE, state.getValue(SLAB_TYPE)));
                }
            }
        }
    }
}
