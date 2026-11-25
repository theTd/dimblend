package dimblend.blocks.compat.create.funnel;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.api.equipment.goggles.IHaveHoveringInformation;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.logistics.funnel.FunnelFilterSlotPositioning;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import com.simibubi.create.foundation.item.ItemHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 卸料漏斗的方块实体。
 *
 * <p>抽取判定复用 Create 漏斗的抽取管线（冷却、翻板动画、智能观测器通知、物品弹出）：
 * <ul>
 *   <li>立式形态：{@code FunnelBlockEntity#tick} 判定 {@code Mode.EXTRACT} 后走
 *       {@code activateExtractor()}，量/模式由本类覆写的
 *       {@link #getAmountToExtract()}（恒 1）与 {@link #getModeToExtract()}（恒
 *       {@code EXACTLY}）钉死，候选筛选则落在 {@link DumpFilteringBehaviour#test}——
 *       Create 的 {@code InvManipulationBehaviour#getFilterTest} 查的就是挂在
 *       {@code FilteringBehaviour.TYPE} 上的第一条行为，本类在
 *       {@link #addBehaviours} 里把黄铜漏斗那条替换成卸料版本。</li>
 *   <li>传送带形态：{@code determineCurrentMode()} 只认 Create 自家的
 *       {@code BeltFunnelBlock}，第三方子类会被判成 {@code INVALID}，因此本类在
 *       {@link #tick} 里自己实现"推给下方传送带"这一段（见 {@link #pushToBelt()}）。</li>
 * </ul>
 *
 * <p><b>锁定判定 #17</b>：{@link #countMatchingInTarget} 按 {@code Item + DataComponents}
 * 汇总容器内的<b>总件数</b>，每个不同物品各算各的阈值。每 tick 汇总一次并缓存，
 * 避免 Create 逐槽调用筛选谓词造成的 O(n²) 扫描。</p>
 */
public class DumpFunnelBlockEntity extends FunnelBlockEntity implements IHaveHoveringInformation {

    /** 传送带形态两次推送之间的冷却（对齐 Create 漏斗默认抽取计时 8gt）。 */
    private static final int BELT_PUSH_COOLDOWN = 8;

    private DumpFilteringBehaviour dumpFilter;
    private InvManipulationBehaviour invManipulationBehaviour;
    private int beltCooldown;

    private final List<DumpFunnelRules.ItemCount> perItemTotals = new ArrayList<>();
    private long totalsCacheTick = Long.MIN_VALUE;

    public DumpFunnelBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        // 黄铜漏斗默认挂一条 FilteringBehaviour（活跃条件只认自家方块）；这里整条替换成
        // 卸料版本，保证 getBehaviour(FilteringBehaviour.TYPE) 取到的是卸料判定。
        DumpFilteringBehaviour replacement = new DumpFilteringBehaviour(this, new FunnelFilterSlotPositioning());
        int index = -1;
        for (int i = 0; i < behaviours.size(); i++) {
            if (behaviours.get(i) instanceof FilteringBehaviour && !(behaviours.get(i) instanceof DumpFilteringBehaviour)) {
                index = i;
            }
        }
        if (index >= 0) {
            behaviours.set(index, replacement);
        } else {
            behaviours.add(replacement);
        }
        this.dumpFilter = replacement;
    }

    @Override
    public int getAmountToExtract() {
        return DumpFunnelRules.EXTRACT_AMOUNT;
    }

    @Override
    public ItemHelper.ExtractionCountMode getModeToExtract() {
        return ItemHelper.ExtractionCountMode.EXACTLY;
    }

    @Override
    public void tick() {
        // 立式形态的抽取完全交给父类（Mode.EXTRACT + 覆写的量/模式 + 卸料筛选谓词）
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (beltCooldown > 0) {
            beltCooldown--;
            return;
        }
        if (getBlockState().getBlock() instanceof DumpBeltFunnelBlock) {
            pushToBelt();
        }
    }

    /**
     * 传送带形态：抽 1 个满足阈值的物品塞进下方传送带。
     */
    private void pushToBelt() {
        BlockState state = getBlockState();
        BeltFunnelBlock.Shape shape = state.getValue(DumpBeltFunnelBlock.SHAPE);
        if (shape == BeltFunnelBlock.Shape.PULLING) {
            // 永不允许从传送带上抽取
            return;
        }
        Direction facing = AbstractFunnelBlock.getFunnelFacing(state);
        if (facing == null) {
            return;
        }
        DirectBeltInputBehaviour input = BlockEntityBehaviour.get((BlockGetter) level, worldPosition.below(),
                DirectBeltInputBehaviour.TYPE);
        if (input == null || !input.canInsertFromSide(facing) || input.isOccupied(facing)) {
            return;
        }
        InvManipulationBehaviour inv = invManipulation();
        if (inv == null) {
            return;
        }
        ItemStack extracted = inv.extract(ItemHelper.ExtractionCountMode.EXACTLY, DumpFunnelRules.EXTRACT_AMOUNT);
        // 空手也压冷却：满容器时逐 tick 全表扫描没有必要，8gt 内新填满的物品等得起
        beltCooldown = BELT_PUSH_COOLDOWN;
        if (extracted.isEmpty()) {
            return;
        }
        flap(false);
        onTransfer(extracted);
        input.handleInsertion(extracted, facing, false);
    }

    private InvManipulationBehaviour invManipulation() {
        if (invManipulationBehaviour == null) {
            invManipulationBehaviour = getBehaviour(InvManipulationBehaviour.TYPE);
        }
        return invManipulationBehaviour;
    }

    /**
     * 容器内与 {@code candidate} 完全同种（{@code Item + DataComponents}）的物品总件数。
     */
    public long countMatchingInTarget(ItemStack candidate) {
        if (candidate.isEmpty() || level == null) {
            return 0L;
        }
        long now = level.getGameTime();
        if (now != totalsCacheTick) {
            rebuildTotals();
            totalsCacheTick = now;
        }
        return DumpFunnelRules.totalOf(perItemTotals, candidate);
    }

    private void rebuildTotals() {
        perItemTotals.clear();
        InvManipulationBehaviour inv = invManipulation();
        if (inv == null) {
            return;
        }
        IItemHandler handler = inv.getInventory();
        if (handler == null) {
            return;
        }
        int slots = handler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            DumpFunnelRules.merge(perItemTotals, handler.getStackInSlot(slot));
        }
    }

    /** 阈值（组）。 */
    public int configuredStacks() {
        return dumpFilter == null ? DumpFunnelRules.DEFAULT_STACKS : dumpFilter.getStacks();
    }

    @Override
    public void clearContent() {
        if (dumpFilter != null) {
            dumpFilter.setFilter(ItemStack.EMPTY);
        } else {
            super.clearContent();
        }
    }

    /**
     * 护目镜信息：没设过滤 = 完全不卸料，必须显式提示（否则玩家只会看到漏斗"不动"）。
     */
    @Override
    public boolean addToTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (dumpFilter != null && dumpFilter.getFilter().isEmpty()) {
            tooltip.add(Component.translatable("dimblend_blocks.dump_funnel.no_filter")
                    .withStyle(ChatFormatting.GRAY));
            return true;
        }
        return false;
    }
}
