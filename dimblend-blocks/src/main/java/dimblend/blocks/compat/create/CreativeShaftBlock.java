package dimblend.blocks.compat.create;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import com.simibubi.create.foundation.placement.PoleHelper;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
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
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 创造模式传动杆：行为与 create:shaft 一致，差异：
 * <ul>
 * <li>BE 用本模组自己的 {@code creative_bracketed_kinetic} 类型（Create 的
 * BRACKETED_KINETIC 白名单注册期固定，第三方方块进不去）</li>
 * <li>扳手按模式分派（2026-09-29 拍板）：<b>创造</b>恢复 Create 原版语义
 * （普通右键先拆支架否则旋转轴、潜行右键拆卸不掉落——创造可拆可放无需互转补偿手势）；
 * <b>生存</b>不能调方向不能拆——普通右键有支架先拆支架、否则变成
 * {@link CreativeCogwheelBlock}（同轴同含水，{@code switchToBlockState}
 * 保 BE/转速/支架），潜行右键无操作</li>
 * <li>识别色：旋转本体渲染改用偏黄铜的自有贴图模型，与原版一眼区分
 * （模型/partial 见 {@code CreativeKineticsClient}）</li>
 * <li>套壳保留（安山/黄铜壳，经 EncasingRegistry 映射到本板块套壳变体）；
 * 砍掉上游「金属横梁套壳」分支（会把本方块换成普通横梁包轴，丢失创造属性）</li>
 * <li>蓝图取材：轴继承链（字节码核实至 AbstractShaftBlock）未实现
 * {@code SpecialBlockItemRequirement}，默认取材=本方块自身物品，该物品无配方、
 * 仅创造栏可得，生存模式原理图加农炮无从索取——同 C3 伪装板的在案结论，
 * 故不额外加接口</li>
 * <li>不可破坏/防爆/仅创造可放置由方块 Properties、{@code CreativeOnlyBlockItem}
 * 与 {@link CreativeKineticGuard} 承担</li>
 * </ul>
 *
 * <p>蒸汽引擎零代码结论：{@code SteamEngineBlock.isShaftValid} 是注册身份判定
 * （只认 {@code AllBlocks.SHAFT}/{@code POWERED_SHAFT}），引擎 BE 认领靠
 * {@code instanceof PoweredShaftBlockEntity}——本方块天然永不接入引擎，引擎只是
 * 不工作、无副作用。同理 {@code ShaftBlock.getStateForPlacement} 里的
 * {@code pickCorrectShaftType} 引擎转换对本方块恒等（stillValid 前置同一身份判定），
 * 因此 super 调用是安全的，无需剥离任何引擎逻辑。</p>
 */
public class CreativeShaftBlock extends ShaftBlock {

    /**
     * 创造轴极柱连放：上游 helper 的状态谓词只认原版轴（{@code AllBlocks.SHAFT::has}），
     * 手持创造轴右键创造轴无反应；此 helper 物品/状态谓词都只认创造轴
     * （无 powered 分支——本方块永不接入蒸汽引擎），偏移逻辑与上游一致。
     */
    private static final int CREATIVE_POLE_HELPER_ID =
            PlacementHelpers.register(new CreativePoleHelper());

    private final Supplier<? extends BlockEntityType<? extends KineticBlockEntity>> blockEntityType;

    public CreativeShaftBlock(Properties properties,
            Supplier<? extends BlockEntityType<? extends KineticBlockEntity>> blockEntityType) {
        super(properties);
        this.blockEntityType = blockEntityType;
    }

    @Override
    public BlockEntityType<? extends KineticBlockEntity> getBlockEntityType() {
        return blockEntityType.get();
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        // 创造：恢复 Create 原版扳手语义（先拆支架，否则旋转轴）——创造可拆可放，
        // 不需要互转补偿手势（2026-09-29 拍板：互转是生存唯一改形态手段）
        if (player != null && player.isCreative()) {
            return super.onWrenched(state, context);
        }
        // 生存：不能调方向不能拆，只能互转。支架优先（Create 扳手优先级），
        // 否则变成 {@link CreativeCogwheelBlock}（同轴同含水，switchToBlockState
        // 保 BE/转速/支架）。
        if (tryRemoveBracket(context)) {
            return InteractionResult.SUCCESS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState swapped = CreativeKinetics.CREATIVE_COGWHEEL.get().defaultBlockState()
                .setValue(AXIS, state.getValue(AXIS))
                .setValue(WATERLOGGED, state.getValue(WATERLOGGED));
        // 同 IWrenchable 默认实现的存活检查：齿轮摆不下（邻位冲突）时不变
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
        // 创造：恢复原版潜行拆卸语义（移除不掉落，与 Create 创造手感一致）；
        // 生存：不可扳手拆卸（规格），不调 super（默认实现会拆下本体）
        if (player != null && player.isCreative()) {
            return super.onSneakWrenched(state, context);
        }
        return InteractionResult.PASS;
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
        if (player.isShiftKeyDown() || !player.mayBuild()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        ItemInteractionResult result = tryEncase(state, level, pos, stack, player, hand, hitResult);
        if (result.consumesAction()) {
            return result;
        }

        // 上游的金属横梁套壳分支在此有意省略（见类 javadoc）。

        // 生存旁路封堵：helper 连放（placeInWorld）不走 canPlace/getStateForPlacement，
        // 非创造一律放行到扳手等后续逻辑（放置由 EntityPlaceEvent 守卫兜底）。
        if (player.isCreative()) {
            IPlacementHelper helper = PlacementHelpers.get(CREATIVE_POLE_HELPER_ID);
            if (helper.matchesItem(stack)) {
                return helper.getOffset(player, level, state, pos, hitResult)
                        .placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hitResult);
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @ParametersAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    private static class CreativePoleHelper extends PoleHelper<Direction.Axis> {

        private CreativePoleHelper() {
            super(state -> state.getBlock() instanceof CreativeShaftBlock, state -> state.getValue(AXIS), AXIS);
        }

        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof CreativeShaftBlock;
        }
    }
}
