package dimblend.blocks.compat.deployer.client;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dimblend.blocks.compat.deployer.DumpGaugeMath;
import dimblend.blocks.compat.deployer.DumpPanelBehaviour;
import dimblend.blocks.compat.deployer.DumpPanelLayout;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * 卸料仪表面板屏（标题「卸料设置」）。
 *
 * <p>继承 Create 的 {@link FactoryPanelScreen} 而不是 Deployer 的 {@code BasicPanelScreen}：
 * 本屏只是换外观，但必须保留父类<b>下发配置</b>的能力——{@code removed()} 会走
 * {@code sendIt(null, false)}，把地址框内容、每个输入源的权重、输出数量与承诺间隔一起发给服务端。
 * （Deployer 的 {@code BasicPanelScreen#removed} 会发空地址 + 空输入表，顺手清掉已建的信号连接。）</p>
 *
 * <p><b>底图直接用 Create 自己的</b>：{@code FACTORY_GAUGE_RECIPE}（192×96）+ {@code FACTORY_GAUGE_BOTTOM}（200×64）。
 * 原先自绘 Deployer 的 {@code generic_gauge} 九宫格并按另一套坐标重画格子/描边，才是「格子与底图错位」的来源：
 * 九宫格的中段会被 86/83 与 40/36 的步长平铺，格子叠在拉伸过的底上必然漂移。换成 Create 的整张图之后，
 * 3×3 格子的 2px 描边、箭头 →、产出格、地址羊皮纸、按钮框全都是贴图里烘死的，<b>不再需要也不允许</b>自绘描边。</p>
 *
 * <p><b>摘掉的东西</b>（都只在父类 {@code init()} 里被 {@code addRenderableWidget}，父类字段仍持有实例，
 * 所以 {@code tick()} 与 {@code removed()} 读值不会 NPE）：</p>
 * <ul>
 *   <li>承诺过期 {@link ScrollInput}：卸料仪表没有承诺语义，滑条藏起来，点击该区域也会被
 *       {@link #mouseClicked} 吞掉；贴图上烘死的时钟/承诺包裹槽/滑条框会被一块 {@code #C6C6C6}
 *       抹平，改写成真实的「周期」读数；</li>
 *   <li>3×3 合成按钮：卸料仪表不做合成，父类只在找到配方时才建它。</li>
 * </ul>
 *
 * <p><b>没摘</b>：左侧「+ 连接输入源」与「移动面板」两枚按钮、地址输入框、删除/确认按钮——
 * 它们对卸料仪表都有实际作用，位置也与贴图上烘好的按钮框/羊皮纸槽严格对齐（父类自己的坐标）。</p>
 */
public class DumpPanelScreen extends FactoryPanelScreen {

    /** 棕底上的标签（与旧版一致的暖色，Create 的深灰在 {@code #875A4D} 上几乎看不清）。 */
    private static final int LABEL_COLOR = 0xF2E2C4;
    /** 棕底上的数值。 */
    private static final int VALUE_COLOR = 0xFFE08A;
    /** 占位/未启用/副标题。 */
    private static final int MUTED_COLOR = 0xB9AC96;

    private final DumpPanelBehaviour dumpPanel;

    public DumpPanelScreen(DumpPanelBehaviour behaviour) {
        super(behaviour);
        this.dumpPanel = behaviour;
    }

    /**
     * 本屏的自绘坐标表是按「主体 192×96 + 底栏 64」这片 200×160 的布局排的。
     * 面板挨着打包机时 {@code FactoryPanelBlockEntity} 会把 {@code restocker} 置真，
     * 父类改按 200×104 + 另一套控件坐标排版；那种构图下整体退回 Create 自己的画法，
     * 宁可长得像工厂仪表，也不要把文字画到点不中的位置上。
     */
    private boolean usesDumpLayout() {
        return this.windowWidth == DumpPanelLayout.WINDOW_WIDTH && this.windowHeight == DumpPanelLayout.WINDOW_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        if (!usesDumpLayout()) {
            return;
        }

        List<GuiEventListener> hidden = new ArrayList<>();
        for (GuiEventListener child : this.children()) {
            if (child instanceof ScrollInput) {
                // 承诺过期滑条：卸料仪表不产生承诺
                hidden.add(child);
            } else if (child instanceof IconButton button
                    && button.getX() == this.guiLeft + DumpPanelLayout.CRAFTING_BUTTON_X
                    && button.getY() == this.guiTop + DumpPanelLayout.CRAFTING_BUTTON_Y) {
                // 3×3 合成按钮：父类只在找到合成配方时才建，位置固定（见 FactoryPanelScreen#init）
                hidden.add(child);
            }
        }
        if (!hidden.isEmpty()) {
            this.removeWidgets(hidden);
        }
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!usesDumpLayout()) {
            super.renderWindow(graphics, mouseX, mouseY, partialTicks);
            return;
        }

        int x = this.guiLeft;
        int y = this.guiTop;

        renderBackground(graphics, x, y);
        renderInputs(graphics, x, y, mouseX, mouseY);

        ItemStack filter = this.dumpPanel.getFilter();
        renderMonitoredSlot(graphics, x, y, filter, mouseX, mouseY);
        renderTarget(graphics, x, y, mouseX, mouseY);
        renderFooter(graphics, x, y, mouseX, mouseY);

        // 标题最后画：落在蓝色标题条上，与 Create 同色同位置（图宽 192，父类按 97 居中）
        Component title = Component.translatable("dimblend_blocks.dump_gauge.gui.title");
        graphics.drawString(this.font, title,
                x + DumpPanelLayout.TITLE_CENTER_X - this.font.width(title) / 2, y + DumpPanelLayout.TITLE_Y,
                DumpPanelLayout.TITLE_COLOR, false);

        renderGaugePreview(graphics, x, y, filter);
    }

    // ------------------------------------------------------------------ 各区块

    /** 两张 Create 底图；贴图里烘死的格子/按钮/羊皮纸槽都已对位，此处不再画任何描边。 */
    private void renderBackground(GuiGraphics graphics, int x, int y) {
        AllGuiTextures.FACTORY_GAUGE_RECIPE.render(graphics, x, y);
        AllGuiTextures.FACTORY_GAUGE_BOTTOM.render(graphics, x, y + DumpPanelLayout.BODY_HEIGHT);
    }

    /**
     * 3×3 信号源。<b>顺序不排序</b>：父类 {@code mouseClicked} 的断线热区就是按
     * {@code new ArrayList<>(targetedBy.values())} 的第 i 个元素映射到第 i 格，
     * 排序会让「画在第 i 格的东西」和「点第 i 格断掉的那条连接」错位。
     */
    private void renderInputs(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        List<FactoryPanelConnection> connections = new ArrayList<>(this.dumpPanel.targetedBy.values());
        int shown = Math.min(connections.size(), DumpPanelLayout.INPUT_CAPACITY);

        for (int i = 0; i < shown; i++) {
            FactoryPanelConnection connection = connections.get(i);
            FactoryPanelBehaviour source = sourceOf(connection);
            ItemStack stack = source == null ? ItemStack.EMPTY : safeFilter(source);
            DumpPanelLayout.Rect slot = DumpPanelLayout.inputSlot(i);
            int slotX = x + slot.x();
            int slotY = y + slot.y();

            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX, slotY);
                graphics.renderItemDecorations(this.font, stack, slotX, slotY, Integer.toString(connection.amount));
            }

            if (slot.contains(mouseX - x, mouseY - y)) {
                graphics.renderComponentTooltip(this.font, inputTooltip(source, stack, connection.amount), mouseX, mouseY);
            }
        }

        if (connections.isEmpty()) {
            DumpPanelLayout.Rect area = DumpPanelLayout.inputArea();
            if (area.contains(mouseX - x, mouseY - y)) {
                graphics.renderComponentTooltip(this.font, List.of(
                        Component.translatable("dimblend_blocks.dump_gauge.tip.no_inputs").withStyle(ChatFormatting.GRAY),
                        Component.translatable("dimblend_blocks.dump_gauge.tip.no_inputs_how")
                                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)),
                        mouseX, mouseY);
            }
        }
    }

    private List<Component> inputTooltip(FactoryPanelBehaviour source, ItemStack stack, int amount) {
        MutableComponent head = stack.isEmpty()
                ? Component.translatable("dimblend_blocks.dump_gauge.tip.input_unknown")
                : Component.translatable("dimblend_blocks.dump_gauge.tip.input_item", stack.getHoverName());
        List<Component> lines = new ArrayList<>();
        lines.add(head.withStyle(ChatFormatting.GRAY));
        if (source != null) {
            lines.add(source.getDisplayName().copy().withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("dimblend_blocks.dump_gauge.tip.input_amount", amount)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.translatable("dimblend_blocks.dump_gauge.tip.disconnect")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        return lines;
    }

    /** 「卸料物」槽：槽位是贴图里烘好的产出格（160,48），箭头正指向它。 */
    private void renderMonitoredSlot(GuiGraphics graphics, int x, int y, ItemStack filter, int mouseX, int mouseY) {
        DumpPanelLayout.Rect slot = DumpPanelLayout.monitoredSlot();
        if (!filter.isEmpty()) {
            graphics.renderItem(filter, x + slot.x(), y + slot.y());
        }

        Component label = Component.translatable("dimblend_blocks.dump_gauge.gui.item");
        drawRight(graphics, label, x, y + DumpPanelLayout.MONITORED_LABEL_Y, LABEL_COLOR);

        if (slot.contains(mouseX - x, mouseY - y)) {
            List<Component> lines = new ArrayList<>();
            if (filter.isEmpty()) {
                lines.add(Component.translatable("dimblend_blocks.dump_gauge.tip.no_item")
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("dimblend_blocks.dump_gauge.tip.set_item")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            } else {
                lines.add(filter.getHoverName().copy().withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("dimblend_blocks.dump_gauge.tip.set_item")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    /** 槽下方的「卸料目标」：完整文案（墙面角标是紧凑的 {@code ≤n▤}，这里说清单位与是否来自信号）。 */
    private void renderTarget(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        Component label = Component.translatable("dimblend_blocks.dump_gauge.gui.target");
        Component value = this.dumpPanel.getTargetLabelForScreen();
        drawRight(graphics, label, x, y + DumpPanelLayout.TARGET_LABEL_Y, LABEL_COLOR);
        drawRight(graphics, value, x, y + DumpPanelLayout.TARGET_VALUE_Y,
                this.dumpPanel.count == 0 ? MUTED_COLOR : VALUE_COLOR);

        if (valueHover(mouseX - x, mouseY - y)) {
            List<Component> lines = new ArrayList<>();
            if (this.dumpPanel.hasSignalTarget()) {
                lines.add(Component.translatable("dimblend_blocks.dump_gauge.tip.signal_target")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("dimblend_blocks.dump_gauge.tip.slider_target")
                        .withStyle(ChatFormatting.GRAY));
            }
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    /** 目标文案那一行的粗略命中（标签 + 数值两行的包围盒）。 */
    private boolean valueHover(double localX, double localY) {
        DumpPanelLayout.Rect slot = DumpPanelLayout.monitoredSlot();
        return localX >= slot.x() - 24 && localX < DumpPanelLayout.RIGHT_TEXT_EDGE
                && localY >= DumpPanelLayout.TARGET_LABEL_Y && localY < DumpPanelLayout.TARGET_VALUE_Y + 9;
    }

    /**
     * 底栏。
     *
     * <p>抹平贴图上烘死的时钟 + 承诺包裹槽 + 承诺滑条（x 55..141、y 130..157），改写成真实的检查周期；
     * 左侧 {@code (9, 136)} 只在真绑了物流网络 / 有红石链接连接时才画链接槽（与 Create 的写法一致）；
     * 卸料仪表的包裹槽没有「未兑现承诺」这个概念，所以那一格留空、点击吞掉。</p>
     */
    private void renderFooter(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        DumpPanelLayout.Rect mask = DumpPanelLayout.periodMask();
        graphics.fill(x + mask.x(), y + mask.y(), x + mask.right(), y + mask.bottom(), DumpPanelLayout.FOOTER_GRAY);

        Component period = Component.translatable("dimblend_blocks.dump_gauge.gui.period",
                DumpGaugeMath.secondsText(DumpGaugeMath.CHECK_INTERVAL_TICKS));
        graphics.drawString(this.font, period,
                x + mask.x() + (mask.width() - this.font.width(period)) / 2, y + mask.y() + 9,
                DumpPanelLayout.FOOTER_TEXT_COLOR, false);

        if (mask.contains(mouseX - x, mouseY - y)) {
            graphics.renderComponentTooltip(this.font,
                    List.of(Component.translatable("dimblend_blocks.dump_gauge.tip.interval",
                                    DumpGaugeMath.secondsText(DumpGaugeMath.CHECK_INTERVAL_TICKS),
                                    DumpGaugeMath.MAX_ITEMS_PER_PACKAGE)
                            .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
        }

        if (this.dumpPanel.network != null || !this.dumpPanel.targetedByLinks.isEmpty()) {
            int linkX = x + DumpPanelLayout.LINK_SLOT_X;
            int linkY = y + this.windowHeight - DumpPanelLayout.FOOTER_SLOT_Y_OFFSET;
            AllGuiTextures.FROGPORT_SLOT.render(graphics, linkX - 1, linkY - 1);
            graphics.renderItem(AllBlocks.REDSTONE_LINK.asStack(), linkX, linkY);

            if (mouseX >= linkX && mouseX < linkX + 16 && mouseY >= linkY && mouseY < linkY + 16) {
                graphics.renderComponentTooltip(this.font, List.of(
                        Component.translatable(this.dumpPanel.network != null
                                ? "dimblend_blocks.dump_gauge.tip.link_bound"
                                : "dimblend_blocks.dump_gauge.tip.link_connections")
                                .withStyle(ChatFormatting.GRAY),
                        Component.translatable("dimblend_blocks.dump_gauge.tip.unlink")
                                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)),
                        mouseX, mouseY);
            }
        }

        // 地址框本体是父类建的控件（位置 = guiTop + windowHeight - 51，正好落在羊皮纸槽里）
        if (addressHover(mouseX - x, mouseY - y, this.windowHeight)) {
            graphics.renderComponentTooltip(this.font, List.of(
                    Component.translatable("dimblend_blocks.dump_gauge.gui.address").withStyle(ChatFormatting.GRAY),
                    Component.translatable("dimblend_blocks.dump_gauge.tip.address")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                    Component.translatable("dimblend_blocks.dump_gauge.tip.address_signal")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)),
                    mouseX, mouseY);
        }
    }

    private boolean addressHover(double localX, double localY, int height) {
        DumpPanelLayout.Rect box = DumpPanelLayout.addressBox(height);
        // 羊皮纸槽本身比输入框宽一点，整条都能点
        return localX >= box.x() - 8 && localX < box.right() + 24
                && localY >= box.y() - 4 && localY < box.bottom() + 2;
    }

    /** 3D 仪表预览（同位 Create：scale 4、z=-200），渲染蓝钢卸料仪表本体并在右下方挂当前卸料物。 */
    private void renderGaugePreview(GuiGraphics graphics, int x, int y, ItemStack filter) {
        GuiGameElement.of(this.dumpPanel.getItem().getDefaultInstance())
                .scale(4.0f)
                .at(0.0f, 0.0f, -200.0f)
                .render(graphics, x + DumpPanelLayout.GAUGE_PREVIEW_X, y + DumpPanelLayout.GAUGE_PREVIEW_Y);
        if (!filter.isEmpty()) {
            GuiGameElement.of(filter)
                    .scale(1.625f)
                    .at(0.0f, 0.0f, 100.0f)
                    .render(graphics, x + DumpPanelLayout.GAUGE_PREVIEW_ITEM_X, y + DumpPanelLayout.GAUGE_PREVIEW_ITEM_Y);
        }
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 父类三个热区里，本屏只保留「左键输入格 → 断开该输入源」与「红石链接槽 → 清红石连接」；
     * <b>承诺包裹槽 {@code (68, h-24)} 与承诺滑条区域必须吞掉</b>，否则一次误点就会发出
     * {@code clearPromises} / 清空承诺过期的配置包——本面板根本没有承诺。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!usesDumpLayout()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        double localX = mouseX - this.guiLeft;
        double localY = mouseY - this.guiTop;
        if (DumpPanelLayout.periodMask().contains(localX, localY)
                || DumpPanelLayout.packageSlot(this.windowHeight).contains(localX, localY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------ 工具

    private void drawRight(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.drawString(this.font, text, x + DumpPanelLayout.RIGHT_TEXT_EDGE - this.font.width(text), y, color,
                true);
    }

    private FactoryPanelBehaviour sourceOf(FactoryPanelConnection connection) {
        if (this.minecraft == null || this.minecraft.level == null) {
            return null;
        }
        return FactoryPanelBehaviour.at((BlockAndTintGetter) this.minecraft.level, connection.from);
    }

    private static ItemStack safeFilter(FactoryPanelBehaviour source) {
        ItemStack stack = source.getFilter();
        return stack == null ? ItemStack.EMPTY : stack;
    }
}
