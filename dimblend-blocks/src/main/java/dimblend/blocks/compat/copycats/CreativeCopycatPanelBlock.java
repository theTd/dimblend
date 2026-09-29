package dimblend.blocks.compat.copycats;

import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.decoration.copycat.CopycatPanelBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * C3 创造模式伪装板：行为与 Create 本体伪装板一致，差异同
 * {@link CreativeCopycatSlabBlock}（自有 BE、扳手只撕材质不拆本体；
 * Create 链无蓝图取材接口，该项不适用）。
 *
 * <p>BE 用本模组自己的 {@code CopycatBlockEntity} 类型（只绑定本方块——Create 的
 * COPYCAT 类型白名单注册期固定，第三方方块进不去，同 C1/C2 理由）。</p>
 *
 * <p>蓝图取材：Create 链（CopycatPanelBlock←WaterloggedCopycatBlock←CopycatBlock，
 * 字节码核实）未实现 {@code SpecialBlockItemRequirement}，默认取材=本方块自身
 * 物品；该物品无配方、仅创造栏可得，原理图加农炮在生存模式无法索取，
 * 故无需像 C1/C2 那样显式返回 NONE——此处有意不对齐 beam，是核实后的结论
 *（复核在案），不是遗漏。</p>
 *
 * <p>C5 隐藏：未贴材质时创造玩家空手（主手）右键 → 隐藏；隐藏期间一切物品交互穿透
 * （不贴材质、不连放），扳手右键恢复正常；手持扳手时客户端幽灵显现
 * （渲染见 {@code CreativeCopycatGhostRenderer}）。隐藏状态挂在 BE 上
 * （{@link dimblend.blocks.api.CreativeCopycatHidable}），不走方块状态。</p>
 */
public class CreativeCopycatPanelBlock extends CopycatPanelBlock {

    /**
     * 创造物品侧向连放：原版 helper 的物品谓词只认原版伪装板
     * （{@code AllBlocks.COPYCAT_PANEL::isIn}），手持创造板右键无反应；
     * 此 helper 接受创造板，偏移逻辑与原版一致。
     */
    private static final int CREATIVE_PLACEMENT_HELPER_ID =
            PlacementHelpers.register(new CreativePanelPlacementHelper());

    /** 洁净变体（C4）与原变体共享本类，BE 类型由注册侧注入。 */
    private final Supplier<? extends BlockEntityType<? extends CopycatBlockEntity>> blockEntityType;

    public CreativeCopycatPanelBlock(Properties properties,
            Supplier<? extends BlockEntityType<? extends CopycatBlockEntity>> blockEntityType) {
        super(properties);
        this.blockEntityType = blockEntityType;
    }

    @Override
    public BlockEntityType<? extends CopycatBlockEntity> getBlockEntityType() {
        return blockEntityType.get();
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        // 只撕材质：不调 super（潜行扳手的原版语义是拆掉本体）
        return onWrenched(state, context);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        // 隐藏优先：扳手右键恢复正常（仅创造玩家可改隐藏状态，与放置/破坏同权）
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        // 隐藏手势：创造玩家主手空手 + 未隐藏 + 未贴材质（保持「隐藏 ⇒ 无材质」不变量）。
        // 隐藏块对空手交互完全惰性（先短路，不落到上游 toggleCT 改写 BE 状态）。
        // 注意上游事实：Copycats+ 的 CopycatPanelBlockMixin 在运行期给 CopycatPanelBlock
        // 注入 useWithoutItem=toggleCT（本板块注册必以 copycats 在场为前提，mixin 必然生效），
        // 故 super 不是原版默认 PASS——未命中手势时必须照常放行给上游。
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

    private static boolean hasNoMaterial(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CopycatBlockEntity copycat
                && !copycat.hasCustomMaterial();
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
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        // 隐藏期间一切物品交互穿透（不贴材质、不连放）；扳手恢复不受影响：
        // 扳手逻辑在物品侧 WrenchItem.useOn（本方法 PASS 之后才会走到）。
        if (CreativeCopycatHiding.isHidden(level, pos)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // 生存旁路封堵：helper 连放（placeInWorld）不走 canPlace/getStateForPlacement，
        // 非创造直接走原版材质/扳手逻辑。
        if (player != null && player.isCreative() && !player.isShiftKeyDown() && player.mayBuild()) {
            IPlacementHelper placementHelper = PlacementHelpers.get(CREATIVE_PLACEMENT_HELPER_ID);
            if (placementHelper.matchesItem(stack)) {
                placementHelper.getOffset(player, level, state, pos, hitResult)
                        .placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hitResult);
                return ItemInteractionResult.SUCCESS;
            }
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @ParametersAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    private static class CreativePanelPlacementHelper implements IPlacementHelper {
        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof CreativeCopycatPanelBlock;
        }

        @Override
        public Predicate<BlockState> getStatePredicate() {
            return state -> state.getBlock() instanceof CopycatPanelBlock;
        }

        @Override
        public PlacementOffset getOffset(Player player, Level world, BlockState state, BlockPos pos,
                BlockHitResult ray) {
            List<Direction> directions = IPlacementHelper.orderedByDistanceExceptAxis(pos, ray.getLocation(),
                    state.getValue(FACING)
                            .getAxis(),
                    dir -> world.getBlockState(pos.relative(dir))
                            .canBeReplaced());

            if (directions.isEmpty())
                return PlacementOffset.fail();
            else {
                return PlacementOffset.success(pos.relative(directions.get(0)),
                        s -> s.setValue(FACING, state.getValue(FACING)));
            }
        }
    }
}
