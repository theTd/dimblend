package dimblend.blocks.compat.deployer;

import java.util.List;
import java.util.UUID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelSetItemMenu;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dimblend.blocks.compat.deployer.client.DumpGaugeClient;
import dimblend.blocks.compat.deployer.client.DumpGaugePartialModels;
import net.createmod.catnip.animation.LerpedFloat;
import net.liukrast.deployer.lib.logistics.LogisticallyLinked;
import net.liukrast.deployer.lib.logistics.board.AbstractPanelBehaviour;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.liukrast.deployer.lib.logistics.board.RenderFilterSlot;
import net.liukrast.deployer.lib.logistics.board.connection.PanelConnectionBuilder;
import net.liukrast.deployer.lib.registry.DeployerPanelConnections;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
public class DumpPanelBehaviour extends AbstractPanelBehaviour implements RenderFilterSlot {

    /** 两次发包尝试的最小间隔（tick）：每 tick 查一次网络汇总既浪费又容易重复下单。 */
    public static final int DUMP_INTERVAL_TICKS = 10;

    /** 墙面角标里代表「组」的方块字符，与 Create 自己的 {@code getCountLabelForValueBox} 同字形。 */
    private static final String STACK_GLYPH = "\u25A4";

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

    // ---------------------------------------------------------------- 存档

    /**
     * Deployer {@code AbstractPanelBehaviour#write} 会用一份<b>只含本面板字段</b>的新 tag
     * 覆盖掉 Create 刚写好的 {@code nbt[slotName]}，所以 Create 的过滤物品/目标值/地址/频率
     * 只能在这里补回来。完整机制与证据见 {@link DumpPanelState}。
     *
     * <p>不再重复写 {@code Satisfied/Targeting/TargetedBy/TargetedByLinks}：那几项由父类在同一个 tag 里
     * 已经写好，这里只补父类漏掉的部分。</p>
     */
    @Override
    public void easyWrite(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.put(DumpPanelState.FILTER, getFilter().saveOptional(registries));
        DumpPanelState.putScalars(tag, count, upTo, recipeAddress, recipeOutput, promiseClearingInterval, network);
    }

    /**
     * Deployer {@code AbstractPanelBehaviour#setNetwork} 是空实现（{@code return}）；
     * 必须恢复 Create {@code FactoryPanelBehaviour#setNetwork} 的赋值，否则摆放已绑定频率的卸料仪表会丢掉物流网络。
     */
    @Override
    public void setNetwork(UUID network) {
        this.network = network;
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
     * Deployer 的 {@code FilteringRendererMixin} 对「继承了 {@code AbstractPanelBehaviour} 却没实现
     * {@link RenderFilterSlot}」的行为直接返回 {@code false}——也就是<b>不画过滤槽</b>，
     * 所以卸料仪表在墙上必须声明自己是一个会被画的过滤槽。
     */
    @Override
    public boolean renderSlot() {
        return true;
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

    /**
     * 只有设了卸料物才谈得上目标值：Create 自己的 {@code isCountVisible()} 也是
     * {@code !getFilter().isEmpty()}。
     *
     * <p>这条同时决定 {@code acceptsValueSettings()}（{@code FilteringBehaviour} 里就是直接返回它），
     * 也就决定了右键是「拖滑条」还是「短按交互」：过滤槽为空时没有可设的目标，
     * 右键应当去选物品，而不是弹一个目标值没意义的滑条。父类
     * {@code AbstractPanelBehaviour#isCountVisible()} 恒返回 {@code true}，于是没设物品时
     * 墙上会印着一行「未启用」、右键还会进拖拽分支吞掉交互。</p>
     */
    @Override
    public boolean isCountVisible() {
        return !getFilter().isEmpty();
    }

    // ---------------------------------------------------------------- 右键

    /**
     * 短按右键的统一入口（Deployer 给自定义面板留的 5 参钩子；4 参版本在
     * {@code AbstractPanelBehaviour} 里是 {@code final}，它先处理扳手循环连线、
     * 再交给客户端连接处理器，最后才调到这里）。
     *
     * <p>判定全在 {@link DumpGaugeInteraction#route} 里，这里只负责执行：</p>
     * <ul>
     *   <li>过滤槽为空 + 空手（服务端）→ 打开 Create 的「选择物品」幽灵槽界面；</li>
     *   <li>过滤槽已设 + 空手（客户端）→ 打开「卸料设置」；</li>
     *   <li>手持普通物品（服务端）→ 直接写成卸料物；</li>
     *   <li>手持物流网络物品（服务端）→ 绑频率；</li>
     *   <li>扳手 / 机械臂 → 两侧都不动（扳手要留给连线走向循环、机械臂要放下）。</li>
     * </ul>
     */
    @Override
    public void onShortInteract(Player player, InteractionHand hand, Direction side, BlockHitResult hitResult,
            boolean client) {
        ItemStack held = player.getItemInHand(hand);
        boolean frequencyItem = isFrequencyItem(held);
        boolean candidate = !frequencyItem && canShortInteract(held);
        DumpGaugeInteraction.Click click = new DumpGaugeInteraction.Click(client, getFilter().isEmpty(),
                held.isEmpty(), frequencyItem, candidate);
        switch (DumpGaugeInteraction.route(click)) {
            case APPLY_FILTER -> applyFilterFromHand(player, side, held);
            case OPEN_SET_ITEM_MENU -> openSetItemMenu(player);
            case OPEN_SCREEN -> displayScreen(player);
            case ASSIGN_FREQUENCY -> assignFrequency(player, held);
            case CONSUME_ONLY, IGNORE -> {
                // 客户端拿着物品/网络物品时什么都不做（服务端负责写状态）；
                // 扳手、机械臂两侧都不动
            }
        }
    }

    /** 与 Create {@code FilteringBehaviour#canShortInteract} 一致：扳手要用来循环连线，机械臂是要放下的方块。 */
    @Override
    public boolean canShortInteract(ItemStack toApply) {
        return DumpGaugeInteraction.isFilterCandidate(toApply.isEmpty(),
                toApply.is(Tags.Items.TOOLS_WRENCH), AllBlocks.MECHANICAL_ARM.isIn(toApply));
    }

    /** Create 的 {@code FactoryPanelSetItemMenu}；Deployer 的 {@code createMenu} 恒返回 {@code null}，必须覆盖。 */
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return FactoryPanelSetItemMenu.create(containerId, playerInventory, this);
    }

    /**
     * 服务端：把手里物品写进过滤槽。语义照抄 {@code FilteringBehaviour#onShortInteract}——
     * 同一个物品不能被当过滤（{@code setFilter} 返回 false）时只播 deny 音，成功时播框架音。
     */
    private void applyFilterFromHand(Player player, Direction side, ItemStack held) {
        if (!Create.LOGISTICS.mayInteract(network, player)) {
            player.displayClientMessage(Component.translatable("create.logistically_linked.protected")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        Level level = getWorld();
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

    /**
     * 覆写过滤写入，只为补一个「重建面板模型」的脏标记。
     *
     * <p>{@code FactoryPanelBlockEntity#write} 只有在 {@code redraw} 为真时才把 {@code "Redraw"} 标记
     * 写进客户端包，客户端读到时才 {@code sendBlockUpdated} 重建面板形状/过滤物品。
     * 而 {@code FactoryPanelBehaviour#setFilter} 内部是「setChanged → sendData」，
     * <b>发包在返回之前</b>，所以标记必须在调父类之前置位——否则这次同步里没有 Redraw，
     * 墙上那张面板要等下一次方块更新才会显示新的过滤物品。</p>
     *
     * <p>除了手里直接右键，Create 的 {@code FactoryPanelSetItemMenu#saveData} 也是直接调这个方法，
     * 所以放在这里能同时覆盖两条路径。</p>
     */
    @Override
    public boolean setFilter(ItemStack stack) {
        panelBE().redraw = true;
        return super.setFilter(stack);
    }

    /** 物流网络物品（Create 的 stock link / Deployer 自己的网络物品）：右键用意是绑频率。 */
    private static boolean isFrequencyItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof LogisticallyLinkedBlockItem || item instanceof LogisticallyLinked;
    }

    /** 服务端：把手里物品的网络频率写到面板上（与 Create 工厂仪表同口径）。 */
    private void assignFrequency(Player player, ItemStack held) {
        if (!Create.LOGISTICS.mayInteract(network, player)) {
            player.displayClientMessage(Component.translatable("create.logistically_linked.protected")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        LogisticallyLinkedBlockItem.assignFrequency(held, player, network);
    }

    /** 服务端：打开 Create 的「选择物品」幽灵槽界面，参数编码与 {@code FactoryPanelBehaviour#onShortInteract} 一致。 */
    private void openSetItemMenu(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        serverPlayer.openMenu(this, buffer -> FactoryPanelPosition.STREAM_CODEC.encode(buffer, getPanelPosition()));
    }

    /**
     * 接入上限。
     *
     * <p>Create 的 {@code FactoryPanelScreen} 只画 9 个输入格，但它的断线热区是按
     * <b>{@code targetedBy} 的第 i 个元素</b>算 {@code (68+i%3*20, 28+i/3*20)} 的，没有上限：
     * 第 10 条以后的连接会映射到格子外面（y=88 以下、甚至底栏），点空白处就会莫名断掉一条输入。
     * 本面板只对「指进来」的方向限流（{@code canBePointed} 的接收方就是被指的那个面板），
     * 这样第 10 条连接在建立时就被拒绝，界面里永远不会出现点不到的连接。</p>
     */
    @Override
    public String canBePointed(FactoryPanelBehaviour other) {
        String base = super.canBePointed(other);
        if (base != null) {
            return base;
        }
        if (targetedBy.size() >= DumpPanelLayout.INPUT_CAPACITY
                && !targetedBy.containsKey(other.getPanelPosition())) {
            return "卸料仪表最多接入 " + DumpPanelLayout.INPUT_CAPACITY + " 个信号源";
        }
        return null;
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
        return Component.translatable(settings.row() == 1
                ? "dimblend_blocks.dump_gauge.value.stacks"
                : "dimblend_blocks.dump_gauge.value.items", units);
    }

    /**
     * 墙面角标：必须短。Create 自己在同一位置画的是 {@code "24▤/3▤"}，而面板本体只有十来个像素宽，
     * 「卸到 ≤ 64 个」这种整句会直接溢出到方块外面（也是原先墙上一片糊字的来源）。
     */
    @Override
    public MutableComponent getCountLabelForValueBox() {
        Integer units = DumpGaugeMath.displayUnits(count);
        if (units == null) {
            return Component.literal("-").withStyle(ChatFormatting.DARK_GRAY);
        }
        return Component.literal("\u2264" + units + (upTo ? STACK_GLYPH : ""));
    }

    /** 设置页里的完整目标文案（墙面角标走 {@link #getCountLabelForValueBox()} 的紧凑格式）。 */
    public MutableComponent getTargetLabelForScreen() {
        Integer units = DumpGaugeMath.displayUnits(count);
        if (units == null) {
            return Component.translatable("dimblend_blocks.dump_gauge.value.disabled").withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable(upTo
                ? "dimblend_blocks.dump_gauge.value.stacks"
                : "dimblend_blocks.dump_gauge.value.items", units);
    }

    /** 设置页要标出「这个目标是不是来自外部信号」，此时滑条值不生效。 */
    public boolean hasSignalTarget() {
        return getConnectionValue(DeployerPanelConnections.NUMBERS.get()).isPresent();
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
