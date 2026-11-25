package dimblend.blocks.compat.create.funnel;

import java.util.List;

import com.simibubi.create.content.logistics.funnel.FunnelFilterSlotPositioning;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 卸料漏斗的过滤槽：单行「大于（组）」阈值滑条 + 「{@code >N组}」角标。
 *
 * <p>与黄铜漏斗过滤槽的关键差异：</p>
 * <ul>
 *   <li>滑条只有<b>一行</b>（Create 的「到/恰好」第二行被删除），值域 {@code 1..64} 组，
 *       里程碑 16 组，默认 1 组，且 {@code <1} 一律夹到 1（见 {@link DumpFunnelRules#clampStacks}）；</li>
 *   <li>{@link #test(ItemStack)} 不是"物品类型匹配"而是"物品类型匹配<b>且</b>容器里这个物品
 *       的<b>总数</b>超过阈值"——Create 的 {@code InvManipulationBehaviour#getFilterTest}
 *       会拿这个谓词筛抽取候选，于是抽取量天然受 {@link DumpFunnelRules#shouldDump} 管住；</li>
 *   <li>过滤槽为空时 {@code test} 恒 {@code false}：卸料漏斗不设过滤就完全不抽，
 *       护目镜提示见 {@code DumpFunnelBlockEntity#addToTooltip}。</li>
 * </ul>
 */
public class DumpFilteringBehaviour extends FilteringBehaviour {

    public DumpFilteringBehaviour(SmartBlockEntity blockEntity, FunnelFilterSlotPositioning slot) {
        super(blockEntity, slot);
        // 默认 1 组、单位是组（不是 Create 的"个"）
        this.count = DumpFunnelRules.DEFAULT_STACKS;
        this.upTo = false;
        // Create 的 showCount 字段是包内可见，跨包只能走这个公开的链式方法
        showCount();
    }

    /** 当前配置的阈值（组），永远 {@code 1..64}。 */
    public int getStacks() {
        return DumpFunnelRules.clampStacks(this.count);
    }

    @Override
    public boolean test(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (getFilter().isEmpty()) {
            return false;
        }
        if (!super.test(stack)) {
            return false;
        }
        if (!(blockEntity instanceof DumpFunnelBlockEntity dumpEntity)) {
            return false;
        }
        return DumpFunnelRules.shouldDump(dumpEntity.countMatchingInTarget(stack), getStacks(),
                stack.getMaxStackSize());
    }

    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean ctrlDown) {
        int stacks = DumpFunnelRules.clampStacks(settings.value());
        if (stacks == getStacks() && !this.upTo) {
            return;
        }
        this.count = stacks;
        this.upTo = false;
        this.blockEntity.setChanged();
        this.blockEntity.sendData();
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, getStacks());
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(
                Component.translatable("dimblend_blocks.dump_funnel.board.title"),
                DumpFunnelRules.MAX_STACKS,
                DumpFunnelRules.MILESTONE_INTERVAL,
                List.of(Component.translatable("dimblend_blocks.dump_funnel.board.row")),
                new ValueSettingsFormatter(DumpFilteringBehaviour::formatStacks));
    }

    /** 「{@code >N组}」：单行滑条的行标签与值框同一口径。 */
    public static MutableComponent formatStacks(ValueSettings settings) {
        return Component.translatable("dimblend_blocks.dump_funnel.value",
                DumpFunnelRules.clampStacks(settings.value()));
    }

    @Override
    public MutableComponent getCountLabelForValueBox() {
        return Component.translatable("dimblend_blocks.dump_funnel.value", getStacks());
    }

    /** 单位是组，与原版物品上限无关，永远要显示数量角标。 */
    @Override
    public boolean isCountVisible() {
        return true;
    }

    @Override
    public boolean acceptsValueSettings() {
        return true;
    }
}
