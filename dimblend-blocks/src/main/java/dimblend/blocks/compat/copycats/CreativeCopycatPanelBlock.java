package dimblend.blocks.compat.copycats;

import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.decoration.copycat.CopycatPanelBlock;
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
 */
public class CreativeCopycatPanelBlock extends CopycatPanelBlock {

    /**
     * 创造物品侧向连放：原版 helper 的物品谓词只认原版伪装板
     * （{@code AllBlocks.COPYCAT_PANEL::isIn}），手持创造板右键无反应；
     * 此 helper 接受创造板，偏移逻辑与原版一致。
     */
    private static final int CREATIVE_PLACEMENT_HELPER_ID =
            PlacementHelpers.register(new CreativePanelPlacementHelper());

    public CreativeCopycatPanelBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends CopycatBlockEntity> getBlockEntityType() {
        return CreativeCopycats.CREATIVE_CREATE_COPYCAT.get();
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        // 只撕材质：不调 super（潜行扳手的原版语义是拆掉本体）
        return onWrenched(state, context);
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
