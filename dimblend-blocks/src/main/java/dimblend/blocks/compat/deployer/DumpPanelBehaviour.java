package dimblend.blocks.compat.deployer;

import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dimblend.blocks.compat.deployer.client.DumpGaugeClient;
import dimblend.blocks.compat.deployer.client.DumpGaugePartialModels;
import net.createmod.catnip.animation.LerpedFloat;
import net.liukrast.deployer.lib.logistics.board.AbstractPanelBehaviour;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.liukrast.deployer.lib.logistics.board.connection.PanelConnectionBuilder;
import net.liukrast.deployer.lib.registry.DeployerPanelConnections;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.Tags;

/**
 * 卸料仪表：把"库存压到目标值以下"做成一张物流面板。
 *
 * <p>与库存面板方向相反——库存面板管"补到 ≥ 目标"，本仪表管"卸到 ≤ 目标"，
 * 所以只接纯信号面板（{@code REDSTONE}/{@code NUMBERS}/{@code STRING}），
 * 任何提供库存连接的面板都会被 {@link #canConnect} 拒绝，避免和补货逻辑互相拉扯。</p>
 *
 * <p>滑条映射见 {@link DumpGaugeMath}（锁定判定 #19）：0 未启用、1 目标 0、2..100 → 1..99。
 * 外部 {@code NUMBERS} 信号直接给 units（负值 = 未启用），与滑条共用同一件数换算。</p>
 *
 * <p>外观：暗钢蓝重着色面板（贴图 {@code block/dump_gauge/dump_panel*}，
 * 部分模型见 {@link dimblend.blocks.compat.deployer.client.DumpGaugePartialModels}），
 * 屏为 {@link dimblend.blocks.compat.deployer.client.DumpPanelScreen}，标题「卸料设置」。</p>
 */
public class DumpPanelBehaviour extends AbstractPanelBehaviour {

    /** 两次发包尝试的最小间隔（tick）：每 tick 查一次网络汇总既浪费又容易重复下单。 */
    public static final int DUMP_INTERVAL_TICKS = 10;

    private static ValueSettingsFormatter formatter;

    public DumpPanelBehaviour(PanelType<?> type, FactoryPanelBlockEntity panelBE, FactoryPanelBlock.PanelSlot slot) {
        super(type, panelBE, slot);
    }

    // ---------------------------------------------------------------- 连接

    @Override
    public void addConnections(PanelConnectionBuilder builder) {
        builder.registerInput(DeployerPanelConnections.REDSTONE)
                .registerInput(DeployerPanelConnections.NUMBERS)
                .registerInput(DeployerPanelConnections.STRING);
    }

    /** 只允许接"纯信号"面板：库存连接与卸料语义冲突。 */
    @Override
    public String canConnect(FactoryPanelBehaviour other) {
        String base = super.canConnect(other);
        if (base != null) {
            return base;
        }
        if (other == this) {
            return "不能连接自己";
        }
        if (other instanceof DumpPanelBehaviour) {
            return "两张卸料仪表之间没有可传递的信息";
        }
        if (other instanceof AbstractPanelBehaviour them
                && (them.getInputConnections().contains(DeployerPanelConnections.STOCK_CONNECTION.get())
                        || them.getOutputConnections().contains(DeployerPanelConnections.STOCK_CONNECTION.get()))) {
            return "库存面板不支持卸料目标，请改用数值/信号面板";
        }
        if (!(other instanceof AbstractPanelBehaviour)) {
            // 原版工厂面板隐含提供库存连接，一律拒绝
            return "只能连接到提供数值或红石信号的面板";
        }
        return null;
    }

    // ---------------------------------------------------------------- 展示

    @Override
    public Item getItem() {
        return DumpGauges.DUMP_GAUGE_ITEM.get();
    }

    @Override
    public PartialModel getModel(FactoryPanelBlock.PanelState state, FactoryPanelBlock.PanelType type) {
        DumpGaugePartialModels.init();
        boolean active = state == FactoryPanelBlock.PanelState.ACTIVE;
        if (type == FactoryPanelBlock.PanelType.PACKAGER) {
            return active ? DumpGaugePartialModels.PANEL_RESTOCKER_WITH_BULB
                    : DumpGaugePartialModels.PANEL_RESTOCKER;
        }
        return active ? DumpGaugePartialModels.PANEL_WITH_BULB : DumpGaugePartialModels.PANEL;
    }

    /**
     * 打开「卸料设置」屏。
     *
     * <p>照抄 Deployer {@code AbstractPanelBehaviour#displayScreen} 的写法：不在方法上打
     * {@code @OnlyIn(CLIENT)}，而是用 {@code LocalPlayer} 判据把客户端分支挡住——
     * 专用服务端永远不会真执行到这里，类的链接/校验因此不会去加载客户端类。</p>
     */
    @Override
    public void displayScreen(Player player) {
        if (player != null && player.level().isClientSide) {
            DumpGaugeClient.openScreen(this);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.dimblend_blocks.dump_gauge");
    }

    /** 基类把过滤槽硬编码成 EMPTY；面板要真的显示手里的过滤物品。 */
    @Override
    public ItemStack getFilter() {
        return filter.item();
    }

    // ---------------------------------------------------------------- 右键

    /**
     * 短按右键：<b>手持物品 → 直接设过滤</b>，空手 → 打开「卸料设置」页。
     *
     * <p>这是 Deployer 给自定义面板留的 5 参钩子（4 参版本在 {@code AbstractPanelBehaviour}
     * 里是 {@code final}）。默认实现只有「客户端就开页面」，正好把「手持物品设过滤」吞掉：
     * 过滤槽为空时 {@code acceptsValueSettings()} 为 false，Create 的
     * {@code ValueSettingsInputHandler} 会直接在双端调这里，于是客户端弹页面、服务端什么也没做。
     * 分支本身放在 {@link DumpGaugeInteraction} 里，单测钉死「拿着物品时客户端不许开页面」。</p>
     */
    @Override
    public void onShortInteract(Player player, InteractionHand hand, Direction side, BlockHitResult hitResult,
            boolean client) {
        ItemStack held = player.getItemInHand(hand);
        switch (DumpGaugeInteraction.route(client, held.isEmpty(), canShortInteract(held))) {
            case APPLY_FILTER -> applyFilterFromHand(player, side, held);
            case OPEN_SCREEN -> displayScreen(player);
            case CONSUME_ONLY, IGNORE -> {
                // 客户端拿着物品时什么都不做（服务端负责写过滤）；扳手/机械臂两侧都不动，
                // 空手短按在服务端也不需要写状态
            }
        }
    }

    /** 与 Create {@code FilteringBehaviour#canShortInteract} 一致：扳手要用来循环连线，机械臂是要放下的方块。 */
    @Override
    public boolean canShortInteract(ItemStack toApply) {
        return DumpGaugeInteraction.isFilterCandidate(toApply.isEmpty(),
                toApply.is(Tags.Items.TOOLS_WRENCH), AllBlocks.MECHANICAL_ARM.isIn(toApply));
    }

    /**
     * 服务端：把手里物品写进过滤槽。语义照抄 {@code FilteringBehaviour#onShortInteract}——
     * 同一个物品不能被当过滤（{@code setFilter} 返回 false）时只播 deny 音，成功时播框架音。
     *
     * <p>{@code redraw} 必须先于 {@code setFilter} 置位：{@code FactoryPanelBlockEntity#write}
     * 只有在 {@code redraw} 为真时才把 "Redraw" 标记写进客户端包，客户端收到后才
     * {@code sendBlockUpdated} 重建面板模型，过滤物品才会出现在面板上。</p>
     */
    private void applyFilterFromHand(Player player, Direction side, ItemStack held) {
        if (!Create.LOGISTICS.mayInteract(network, player)) {
            player.displayClientMessage(Component.translatable("create.logistically_linked.protected")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        Level level = getWorld();
        panelBE().redraw = true;
        if (!setFilter(side, held.copy())) {
            player.displayClientMessage(Component.translatable("create.logistics.filter.invalid_item")
                    .withStyle(ChatFormatting.RED), true);
            if (level != null) {
                AllSoundEvents.DENY.playOnServer(level, panelBE().getBlockPos(), 1.0F, 1.0F);
            }
            return;
        }
        if (level != null) {
            level.playSound(null, panelBE().getBlockPos(), SoundEvents.ITEM_FRAME_ADD_ITEM,
                    SoundSource.BLOCKS, 0.25F, 0.1F);
        }
    }

    // ---------------------------------------------------------------- 滑条

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        if (formatter == null) {
            formatter = new ValueSettingsFormatter(vs -> formatValue(vs));
        }
        return new ValueSettingsBoard(
                Component.translatable("dimblend_blocks.dump_gauge.board.title"),
                DumpGaugeMath.RAW_MAX, 10,
                List.of(Component.translatable("dimblend_blocks.dump_gauge.board.row.items"),
                        Component.translatable("dimblend_blocks.dump_gauge.board.row.stacks")),
                formatter);
    }

    @Override
    public void setValueSettings(Player player, ValueSettingsBehaviour.ValueSettings settings, boolean ctrlDown) {
        int raw = DumpGaugeMath.clampRaw(settings.value());
        boolean stacks = settings.row() == 1;
        if (count == raw && upTo == stacks && !ctrlDown) {
            return;
        }
        count = raw;
        upTo = stacks;
        // 与 Create 的 FactoryPanelBehaviour#setValueSettings 对齐：先标 redraw 再发包，
        // 客户端才会带 "Redraw" 标记重建面板模型（否则滑条改了、面板上显示的还是旧值）
        panelBE().redraw = true;
        panelBE().sendData();
    }

    @Override
    public ValueSettingsBehaviour.ValueSettings getValueSettings() {
        return new ValueSettingsBehaviour.ValueSettings(upTo ? 1 : 0, count);
    }

    @Override
    public MutableComponent formatValue(ValueSettingsBehaviour.ValueSettings settings) {
        Integer units = DumpGaugeMath.displayUnits(settings.value());
        if (units == null) {
            return Component.translatable("dimblend_blocks.dump_gauge.value.disabled").withStyle(ChatFormatting.GRAY);
        }
        boolean stacks = settings.row() == 1;
        return Component.translatable(stacks
                ? "dimblend_blocks.dump_gauge.value.stacks"
                : "dimblend_blocks.dump_gauge.value.items", units);
    }

    @Override
    public MutableComponent getCountLabelForValueBox() {
        Integer units = DumpGaugeMath.displayUnits(count);
        if (units == null) {
            return Component.translatable("dimblend_blocks.dump_gauge.value.disabled").withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable(upTo
                ? "dimblend_blocks.dump_gauge.value.stacks"
                : "dimblend_blocks.dump_gauge.value.items", units);
    }

    // ---------------------------------------------------------------- 下料

    @Override
    public void tick() {
        super.tick();
        Level level = getWorld();
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % DUMP_INTERVAL_TICKS != 0) {
            return;
        }
        try {
            dump();
        } catch (RuntimeException e) {
            // 面板只做"尽力而为"：网络汇总或发包炸了也不能拖垮方块实体的 tick
            dimblend.blocks.DimBlendBlocks.LOGGER.warn("dump_gauge failed to dispatch at {}", panelBE().getBlockPos(), e);
        }
    }

    private void dump() {
        if (network == null || !isActive() || redstonePowered) {
            return;
        }
        ItemStack sample = filter.item();
        if (sample.isEmpty()) {
            return; // 没设过滤 = 不知道该卸什么
        }
        String address = resolveAddress();
        if (address.isBlank()) {
            return;
        }
        int units = resolveUnits();
        if (units < 0) {
            return; // 未启用
        }
        InventorySummary summary = LogisticsManager.getSummaryOfNetwork(network, true);
        if (summary == null || summary.isEmpty()) {
            return;
        }
        int inStorage = summary.getCountOf(sample);
        int amount = DumpGaugeMath.dumpAmountForUnits(units, upTo, Math.max(1, sample.getMaxStackSize()), inStorage);
        if (amount <= 0) {
            return;
        }
        List<BigItemStack> stacks = List.of(new BigItemStack(sample.copyWithCount(1), amount));
        boolean sent = LogisticsManager.broadcastPackageRequest(network, RequestType.REDSTONE,
                PackageOrderWithCrafts.simple(stacks), null, address);
        if (sent) {
            if (bulb != null) {
                bulb.chase(1, 0.5f, LerpedFloat.Chaser.EXP);
            }
            panelBE().sendData();
            notifyOutputs();
        }
    }

    /** 外部 NUMBERS 信号优先（&gt;=0 直接当 units，&lt;0 即禁用）；否则吃滑条。 */
    private int resolveUnits() {
        Float signal = getConnectionValue(DeployerPanelConnections.NUMBERS.get()).orElse(null);
        if (signal != null) {
            return DumpGaugeMath.unitsFromSignal(signal);
        }
        return DumpGaugeMath.targetUnits(count);
    }

    /** STRING 连接优先给出地址，否则用面板自填地址。 */
    private String resolveAddress() {
        String fromConnection = getConnectionValue(DeployerPanelConnections.STRING.get()).orElse(null);
        if (fromConnection != null && !fromConnection.isBlank()) {
            return fromConnection.trim();
        }
        return recipeAddress == null ? "" : recipeAddress.trim();
    }
}
