package dimblend.blocks.compat.deployer.client;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dimblend.blocks.compat.deployer.DumpGaugeMath;
import dimblend.blocks.compat.deployer.DumpGauges;
import dimblend.blocks.compat.deployer.DumpPanelBehaviour;
import dimblend.blocks.compat.deployer.DumpPanelLayout;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.liukrast.deployer.lib.logistics.board.screen.BasicPanelScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * 卸料仪表屏（标题「卸料设置」，窗口 200×160）。
 *
 * <p>为什么继承 Create 的 {@link FactoryPanelScreen} 而不是 Deployer 的 {@code BasicPanelScreen}：
 * 本屏只换外观，但必须保留父类<b>下发配置</b>的能力——{@code removed()} 会走
 * {@code sendIt(null, false)}，把地址框内容、每个输入源的数量、目标数量与承诺间隔一起发给服务端。
 * {@code BasicPanelScreen} 的 {@code removed()} 发的是空地址 + 空输入表，会顺手把已建的信号连接清掉。</p>
 *
 * <p>隐藏的两件东西：3×3 合成按钮与承诺队列滑条。它们只在父类 {@code init()} 里被
 * {@code addRenderableWidget}，父类字段仍然持有其实例，因此 {@code tick()} 与 {@code removed()}
 * 读值依旧安全——这里只是把它们从「可渲染/可交互」列表里摘掉。</p>
 *
 * <p>外观：Deployer 的 {@code generic_gauge.png} 九宫格（含右侧偏出的仪表模型预览）。
 * <b>所有自绘内容都按 {@link DumpPanelLayout} 的常量落位</b>，而该常量表逐条对齐父类
 * {@code init()/mouseClicked()} 的硬编码坐标（输入格 68/28 步长 20、地址框 36 / h-51、底栏 h-24），
 * 所以「看到的格子」和「点得中的格子」一定是同一个——原先自绘用了另一套坐标（还被
 * {@code targetedBy} 重排过顺序），画出来的物品和点下去断开的输入源可以对不上。</p>
 *
 * <p>配色：九宫格主体是棕色（{@code #875A4D}），父类自己的文字色（{@code 0x3D3D48}/{@code 0x2B2B33}）
 * 在这块底上是几乎看不清的暗紫灰，所以标签/数值改用暖白与琥珀色并开阴影；格子用 Create 的
 * {@code FROGPORT_SLOT} 18×18 描边，物品不再「浮」在棕底上。</p>
 */
public class DumpPanelScreen extends FactoryPanelScreen {

    /** 标题落在蓝色头带上，沿用 Create 自己的深色（{@code 4013128} 同族）。 */
    private static final int TITLE_COLOR = 0x3D3B48;
    /** 棕底上的标签。 */
    private static final int LABEL_COLOR = 0xF2E2C4;
    /** 棕底上的数值。 */
    private static final int VALUE_COLOR = 0xFFE08A;
    /** 占位/未启用。 */
    private static final int MUTED_COLOR = 0xB9AC96;

    private final DumpPanelBehaviour dumpPanel;

    public DumpPanelScreen(DumpPanelBehaviour behaviour) {
        super(behaviour);
        this.dumpPanel = behaviour;
    }

    @Override
    protected void init() {
        super.init();

        List<GuiEventListener> hidden = new ArrayList<>();
        for (GuiEventListener child : this.children()) {
            if (child instanceof ScrollInput) {
                // 承诺队列：卸料仪表是「无条件卸到目标以下」，没有承诺语义
                hidden.add(child);
            } else if (child instanceof IconButton button
                    && button.getX() == this.guiLeft + 31
                    && button.getY() == this.guiTop + 27) {
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
        // 本屏的坐标表是按 200×160（父类的非 restocker 尺寸）排的。若这张面板挨着打包机，
        // FactoryPanelBlockEntity 会把 restocker 置真，父类改按 200×104 + 另一套控件坐标布局；
        // 那种少见构图下退回 Create 自己的画法，宁可长得像工厂仪表，也不要把格子画到点不中的地方。
        if (this.windowWidth != DumpPanelLayout.WINDOW_WIDTH || this.windowHeight != DumpPanelLayout.WINDOW_HEIGHT) {
            super.renderWindow(graphics, mouseX, mouseY, partialTicks);
            return;
        }

        int x = this.guiLeft;
        int y = this.guiTop;

        renderFrame(graphics, x, y, this.windowWidth, this.windowHeight);
        graphics.drawCenteredString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.title"),
                x + this.windowWidth / 2, y + 4, TITLE_COLOR);

        renderMonitoredItem(graphics, x, y);
        renderTarget(graphics, x, y);
        renderInputs(graphics, x, y);
        renderAddress(graphics, x, y);
        renderFooterSlots(graphics, x, y);

        GuiGameElement.of(DumpGauges.DUMP_GAUGE_ITEM.get().getDefaultInstance())
                .scale(4.0f)
                .at(0.0f, 0.0f, -200.0f)
                .render(graphics, x + this.windowWidth, y + this.windowHeight - 48);
    }

    // ------------------------------------------------------------------ 各区块

    /** 左列：监控物品（过滤槽）。格子比连接按钮（x+31,y+47）靠左，互不遮挡。 */
    private void renderMonitoredItem(GuiGraphics graphics, int x, int y) {
        drawLabel(graphics, Component.translatable("dimblend_blocks.dump_gauge.gui.item"),
                x + DumpPanelLayout.MONITORED_LABEL_X, y + DumpPanelLayout.MONITORED_LABEL_Y);

        int slotX = x + DumpPanelLayout.MONITORED_SLOT_X;
        int slotY = y + DumpPanelLayout.MONITORED_SLOT_Y;
        drawSlotFrame(graphics, slotX, slotY);
        ItemStack filter = this.dumpPanel.getFilter();
        if (!filter.isEmpty()) {
            graphics.renderItem(filter, slotX + 1, slotY + 1);
        }
    }

    /** 右列：目标值（右对齐到窗口内侧，避免长数字顶到九宫格右边框）。 */
    private void renderTarget(GuiGraphics graphics, int x, int y) {
        Component value = this.dumpPanel.getCountLabelForValueBox();
        int rightEdge = x + this.windowWidth - 12;
        Component label = Component.translatable("dimblend_blocks.dump_gauge.gui.target");
        graphics.drawString(this.font, label, rightEdge - this.font.width(label.getString()), y + 22,
                LABEL_COLOR, true);
        graphics.drawString(this.font, value, rightEdge - this.font.width(value.getString()), y + 34,
                DumpGaugeMath.isInactive(this.dumpPanel.count) ? MUTED_COLOR : VALUE_COLOR, true);
    }

    /**
     * 中列：输入信号源。顺序<b>不排序</b>——父类 {@code connections}/{@code inputConfig} 就是
     * {@code new ArrayList<>(targetedBy.values())}，排序会让「画在第 i 格的东西」和
     * 「点第 i 格会断开的那条连接」错位。
     */
    private void renderInputs(GuiGraphics graphics, int x, int y) {
        drawLabel(graphics, Component.translatable("dimblend_blocks.dump_gauge.gui.inputs"),
                x + DumpPanelLayout.INPUT_GRID_X, y + 17);

        List<FactoryPanelConnection> connections = new ArrayList<>(this.dumpPanel.targetedBy.values());
        if (connections.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.no_inputs"),
                    x + DumpPanelLayout.INPUT_GRID_X, y + DumpPanelLayout.INPUT_GRID_Y + 2, MUTED_COLOR, true);
            return;
        }

        int shown = Math.min(connections.size(), DumpPanelLayout.INPUT_CAPACITY);
        for (int i = 0; i < DumpPanelLayout.INPUT_CAPACITY; i++) {
            DumpPanelLayout.Rect slot = DumpPanelLayout.inputSlot(i);
            drawSlotFrame(graphics, x + slot.x(), y + slot.y());
            if (i >= shown) {
                continue;
            }
            ItemStack stack = stackOf(connections.get(i));
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x + slot.x(), y + slot.y());
                graphics.renderItemDecorations(this.font, stack, x + slot.x(), y + slot.y(),
                        Integer.toString(connections.get(i).amount));
            }
        }
        if (connections.size() > shown) {
            graphics.drawString(this.font, Component.literal("+" + (connections.size() - shown)),
                    x + DumpPanelLayout.INPUT_GRID_X, y + DumpPanelLayout.BODY_BOTTOM - 12, MUTED_COLOR, true);
        }
    }

    /** 底部：地址标签（输入框本体是父类建的控件，位置 = guiTop + windowHeight - 51）。 */
    private void renderAddress(GuiGraphics graphics, int x, int y) {
        drawLabel(graphics, Component.translatable("dimblend_blocks.dump_gauge.gui.address"),
                x + DumpPanelLayout.ADDRESS_LABEL_X, y + DumpPanelLayout.ADDRESS_LABEL_Y);
    }

    /** 灰底栏：父类把这两格当热区（x+68 清承诺、x+9 红石重置），这里照着画出来。 */
    private void renderFooterSlots(GuiGraphics graphics, int x, int y) {
        int slotY = y + this.windowHeight - DumpPanelLayout.FOOTER_SLOT_Y_OFFSET;

        int packageX = x + DumpPanelLayout.PACKAGE_SLOT_X;
        drawSlotFrame(graphics, packageX, slotY);
        ItemStack box = PackageStyles.getDefaultBox();
        graphics.renderItem(box, packageX + 1, slotY + 1);
        graphics.renderItemDecorations(this.font, box, packageX + 1, slotY + 1,
                Integer.toString(this.dumpPanel.getPromised()));

        if (!this.dumpPanel.targetedByLinks.isEmpty()) {
            int linkX = x + DumpPanelLayout.LINK_SLOT_X;
            drawSlotFrame(graphics, linkX, slotY);
            graphics.renderItem(AllBlocks.REDSTONE_LINK.asStack(), linkX + 1, slotY + 1);
        }
    }

    private void drawLabel(GuiGraphics graphics, Component text, int x, int y) {
        graphics.drawString(this.font, text, x, y, LABEL_COLOR, true);
    }

    /** 18×18 描边，物品画在 {@code frame + 1}，包围盒正好 16×16 = 父类命中框。 */
    private void drawSlotFrame(GuiGraphics graphics, int itemX, int itemY) {
        AllGuiTextures.FROGPORT_SLOT.render(graphics, itemX - 1, itemY - 1);
    }

    /** 取某个输入源面板当前监控的物品；面板已卸载/被拆时给 EMPTY。 */
    private ItemStack stackOf(FactoryPanelConnection connection) {
        if (this.minecraft == null || this.minecraft.level == null) {
            return ItemStack.EMPTY;
        }
        FactoryPanelBehaviour source = FactoryPanelBehaviour.at(
                (BlockAndTintGetter) this.minecraft.level, connection.from);
        if (source == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = source.getFilter();
        return stack == null ? ItemStack.EMPTY : stack;
    }

    // ------------------------------------------------------------------ 九宫格底图

    /**
     * Deployer {@code generic_gauge.png} 的九宫格切片（与其 {@code BasicPanelScreen} 一致）：
     * 四角 + 上下右中段按 86/83 px 横向平铺、左右中段按 40/36 px 纵向平铺。
     */
    private void renderFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        ResourceLocation bg = BasicPanelScreen.TEXTURE;
        graphics.blit(bg, x, y, 0, 0, 53, 16);
        graphics.blit(bg, x + w - 106 + 53, y, 139, 0, 53, 16);
        graphics.blit(bg, x, y + h - 46 + 15, 0, 56, 53, 32);
        graphics.blit(bg, x + w - 106 + 53, y + h - 46 + 15, 139, 56, 60, 32);

        if (w > 106) {
            int remaining = w - 106;
            int remainingInner = remaining - 3;
            int step = 0;
            if (remaining > 1) {
                graphics.blit(bg, x + w - 106 + 52, y + h - 46 + 15, 138, 56, 1, 32);
            }
            graphics.blit(bg, x + 53, y + h - 46 + 15, 53, 56, 2, 32);
            while (remaining > 0 || remainingInner > 0) {
                if (remaining > 0) {
                    graphics.blit(bg, x + 53 + step * 86, y, 53, 0, Math.min(remaining, 86), 16);
                }
                if (remainingInner > 0) {
                    graphics.blit(bg, x + 55 + step * 83, y + h - 46 + 15, 55, 56,
                            Math.min(remainingInner, 83), 32);
                }
                step++;
                remaining -= 86;
                remainingInner -= 83;
            }
        }

        if (h > 47) {
            int remaining = h - 47;
            int step = 0;
            graphics.blit(bg, x, y + 16, 0, 16, 53, Math.min(remaining, 40));
            graphics.blit(bg, x + w - 106 + 53, y + 16, 139, 16, 53, Math.min(remaining, 40));
            if (w > 106) {
                int innerStep = 0;
                for (int inner = w - 106; inner > 0; inner -= 86) {
                    graphics.blit(bg, x + 53 + innerStep * 86, y + 16, 53, 16,
                            Math.min(inner, 86), Math.min(remaining, 40));
                    innerStep++;
                }
            }
            remaining -= 40;
            while (remaining > 0) {
                graphics.blit(bg, x, y + 56 + step * 36, 0, 20, 53, Math.min(remaining, 36));
                graphics.blit(bg, x + w - 106 + 53, y + 56 + step * 36, 139, 20, 53, Math.min(remaining, 36));
                int innerStep = 0;
                for (int inner = w - 106; inner > 0; inner -= 86) {
                    graphics.blit(bg, x + 53 + innerStep * 86, y + 56 + step * 36, 53, 20,
                            Math.min(inner, 86), Math.min(remaining, 36));
                    innerStep++;
                }
                step++;
                remaining -= 36;
            }
        }
    }
}
