package dimblend.blocks.compat.deployer.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dimblend.blocks.compat.deployer.DumpGauges;
import dimblend.blocks.compat.deployer.DumpPanelBehaviour;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.liukrast.deployer.lib.logistics.board.screen.BasicPanelScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * 卸料仪表屏（标题「卸料设置」）。
 *
 * <p>为什么继承 Create 的 {@link FactoryPanelScreen} 而不是 Deployer 的 {@code BasicPanelScreen}：
 * 本屏只换外观，但必须保留父类<b>下发配置</b>的能力——{@code removed()} 会走
 * {@code sendIt(null, false)}，把地址框内容、每个输入源的数量、目标数量与承诺间隔一起发给服务端。
 * {@code BasicPanelScreen} 的 {@code removed()} 发的是空地址 + 空输入表，会顺手把已建的信号连接清掉。</p>
 *
 * <p>隐藏的两件东西：3×3 合成按钮与承诺队列滑条。它们只在父类 {@code init()} 里被
 * {@code addRenderableWidget}，父类字段仍然持有其实例，因此 {@code tick()} 与 {@code removed()}
 * 读值依旧安全——这里只是把它们从「可渲染/可交互」列表里摘掉，并用
 * {@code removeWidgets(Collection)} 实现真正不显示、不响应点击。</p>
 *
 * <p>外观：Deployer 的 {@code generic_gauge.png} 九宫格（与右侧偏出的仪表模型预览）取代 Create
 * 的 {@code FACTORY_GAUGE_*} 三张贴图，配色与暗钢蓝面板贴图同族。控件位置沿用父类（地址框、
 * 复位、确认、连接、搬迁、3×3 输入格热区），所以点击命中和父类逻辑一一对齐。</p>
 */
public class DumpPanelScreen extends FactoryPanelScreen {

    private static final int TITLE_COLOR = 0x3D3D48;
    private static final int LABEL_COLOR = 0x6E6E78;
    private static final int VALUE_COLOR = 0x2B2B33;
    /** 地址框底衬，让白底输入框落在暗蓝面板上不显得突兀。 */
    private static final int ADDRESS_BOX_BG = 0x33000000;
    /** 空格子/失效输入源的描边。 */
    private static final int EMPTY_SLOT_TINT = 0x22FFFFFF;

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
                // 3×3 合成按钮：父类只在找到合成配方时才建，位置固定
                hidden.add(child);
            }
        }
        if (!hidden.isEmpty()) {
            this.removeWidgets(hidden);
        }
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = this.guiLeft;
        int y = this.guiTop;
        int w = this.windowWidth;
        int h = this.windowHeight;

        renderFrame(graphics, x, y, w, h);
        graphics.drawCenteredString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.title"),
                x + w / 2, y + 5, TITLE_COLOR);

        renderMonitoredItem(graphics, x, y);
        renderTarget(graphics, x, y);
        renderInputs(graphics, x, y);
        renderAddress(graphics, x, y, h);

        GuiGameElement.of(DumpGauges.DUMP_GAUGE_ITEM.get().getDefaultInstance())
                .scale(4.0f)
                .at(0.0f, 0.0f, -200.0f)
                .render(graphics, x + w, y + h - 48);
    }

    // ------------------------------------------------------------------ 各区块

    /** 左列：监控物品（与父类 连接按钮 y+47 之间只留 3 px，别往下压）。 */
    private void renderMonitoredItem(GuiGraphics graphics, int x, int y) {
        graphics.drawString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.item"),
                x + 11, y + 20, LABEL_COLOR, false);
        ItemStack filter = this.dumpPanel.getFilter();
        if (filter.isEmpty()) {
            graphics.fill(x + 33, y + 28, x + 49, y + 44, EMPTY_SLOT_TINT);
            return;
        }
        graphics.renderItem(filter, x + 33, y + 28);
    }

    /** 右列：目标值（父类把输出格画在 x+160,y+48，这里沿用同一条竖向。 */
    private void renderTarget(GuiGraphics graphics, int x, int y) {
        graphics.drawString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.target"),
                x + 150, y + 20, LABEL_COLOR, false);
        graphics.drawString(this.font, this.dumpPanel.getCountLabelForValueBox(),
                x + 150, y + 32, VALUE_COLOR, false);
    }

    /** 中列：输入信号源一览，格子坐标与父类 mouseClicked 的 20 px 网格严格对齐。 */
    private void renderInputs(GuiGraphics graphics, int x, int y) {
        graphics.drawString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.inputs"),
                x + 68, y + 18, LABEL_COLOR, false);

        List<FactoryPanelConnection> connections = new ArrayList<>(this.dumpPanel.targetedBy.values());
        connections.sort(Comparator.comparing(
                (FactoryPanelConnection connection) -> connection.from.pos().toShortString()));
        if (connections.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.no_inputs"),
                    x + 68, y + 32, LABEL_COLOR, false);
            return;
        }

        int shown = Math.min(connections.size(), 9);
        for (int i = 0; i < shown; i++) {
            FactoryPanelConnection connection = connections.get(i);
            int slotX = x + 68 + (i % 3) * 20;
            int slotY = y + 28 + (i / 3) * 20;

            ItemStack stack = stackOf(connection);
            if (stack.isEmpty()) {
                graphics.fill(slotX, slotY, slotX + 16, slotY + 16, EMPTY_SLOT_TINT);
                continue;
            }
            graphics.renderItem(stack, slotX, slotY);
            graphics.renderItemDecorations(this.font, stack, slotX, slotY, Integer.toString(connection.amount));
        }
        if (connections.size() > shown) {
            graphics.drawString(this.font, "+" + (connections.size() - shown), x + 68, y + 92, LABEL_COLOR, false);
        }
    }

    /** 底部：地址框标签与底衬（输入框本体是父类建的控件，位置固定）。 */
    private void renderAddress(GuiGraphics graphics, int x, int y, int h) {
        graphics.drawString(this.font, Component.translatable("dimblend_blocks.dump_gauge.gui.address"),
                x + 36, y + h - 63, LABEL_COLOR, false);
        graphics.fill(x + 34, y + h - 55, x + 148, y + h - 38, ADDRESS_BOX_BG);
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
