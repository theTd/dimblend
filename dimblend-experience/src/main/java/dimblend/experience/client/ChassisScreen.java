package dimblend.experience.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dimblend.experience.chassis.ChassisChoicePayload;
import dimblend.experience.chassis.ChassisPreset;
import dimblend.experience.chassis.ChassisScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 驾驶台的选择页：所有预生成车厢一格一个按钮，点一下就直接生成，没有二次确认。
 *
 * <p>2026-11-15 用户口径：驾驶台是创造调试工具，"取消二次确认 内部包含所有（龙巢车厢除外）
 * 预生成的列车种类 可选直接生成"。所以这里没有冷却期、没有"再次点击确认"——点击立刻发
 * {@link ChassisChoicePayload} 并关闭页面，服务端收到后当场生成。
 *
 * <p><b>15 个预设怎么排。</b>三列（窗口窄时两列）网格，超出列表高度才滚动（鼠标滚轮），
 * 只画完整落在列表区内的按钮，避免半截按钮压在标题或取消按钮上。当前玩家看不到的预设（生存只看
 * 标准底盘、末影龙追逐缺席时隐藏它的两种）在 {@link #init()} 里就过滤掉，服务端还会再判一次。
 */
public final class ChassisScreen extends Screen {
    private static final int PADDING = 12;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int HEADER = 26;
    private static final int FOOTER = 30;
    private static final int MAX_WIDTH = 420;
    private static final int MAX_HEIGHT = 240;

    private final UUID token;
    private final List<ChassisPreset> presets = new ArrayList<>();
    private final List<Button> presetButtons = new ArrayList<>();
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private int columns;
    private int buttonWidth;
    private int contentHeight;
    private int scroll;
    private boolean sent;

    private ChassisScreen(UUID token) {
        super(Component.translatable("screen.dimblend_experience.chassis"));
        this.token = token;
    }

    /** The server's only two instructions: open this token's page, or close it. */
    public static void apply(ChassisScreenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.open()) {
            minecraft.setScreen(new ChassisScreen(payload.token()));
        } else if (minecraft.screen instanceof ChassisScreen screen && screen.token.equals(payload.token())) {
            minecraft.setScreen(null);
        }
    }

    @Override
    protected void init() {
        boolean creative = minecraft.player != null && minecraft.player.isCreative();
        presets.clear();
        presetButtons.clear();
        for (ChassisPreset preset : ChassisPreset.values()) {
            if (preset.visibleTo(creative)) {
                presets.add(preset);
            }
        }
        panelWidth = Math.min(MAX_WIDTH, width - 16);
        panelHeight = Math.min(MAX_HEIGHT, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        columns = panelWidth >= 380 ? 3 : 2;
        buttonWidth = (panelWidth - 2 * PADDING - (columns - 1) * BUTTON_GAP) / columns;
        listTop = top + HEADER;
        listBottom = top + panelHeight - FOOTER;
        contentHeight = ((presets.size() + columns - 1) / columns) * (BUTTON_HEIGHT + BUTTON_GAP);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (ChassisPreset preset : presets) {
            Button button = Button.builder(Component.translatable(preset.translationKey()),
                            pressed -> choose(preset))
                    .tooltip(Tooltip.create(Component.translatable(preset.descriptionKey())))
                    .bounds(0, 0, buttonWidth, BUTTON_HEIGHT).build();
            presetButtons.add(button);
            addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width / 2 - 50, top + panelHeight - 26, 100, 20).build());
        positionButtons();
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (listBottom - listTop));
    }

    /** 只让完整落在列表区里的按钮可见：滚出去的既不画，也点不到。 */
    private void positionButtons() {
        for (int index = 0; index < presetButtons.size(); index++) {
            Button button = presetButtons.get(index);
            int x = left + PADDING + (index % columns) * (buttonWidth + BUTTON_GAP);
            int y = listTop + (index / columns) * (BUTTON_HEIGHT + BUTTON_GAP) - scroll;
            button.setX(x);
            button.setY(y);
            button.visible = y >= listTop && y + BUTTON_HEIGHT <= listBottom;
            if (!button.visible) {
                button.setFocused(false);
            }
        }
    }

    private void choose(ChassisPreset preset) {
        if (sent) {
            return;
        }
        sent = true;
        PacketDistributor.sendToServer(new ChassisChoicePayload(token, preset.id()));
        super.onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY >= listTop && mouseY <= listBottom && maxScroll() > 0) {
            scroll = Math.max(0, Math.min(maxScroll(),
                    scroll - (int) (vertical * (BUTTON_HEIGHT + BUTTON_GAP))));
            positionButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF0101010);
        graphics.renderOutline(left, top, panelWidth, panelHeight, 0xFF8B8B8B);
        graphics.drawCenteredString(font, title, width / 2, top + 8, 0xFFFFFFFF);
        if (maxScroll() > 0) {
            int track = listBottom - listTop;
            int thumb = Math.max(16, track * track / contentHeight);
            int thumbTop = listTop + scroll * (track - thumb) / maxScroll();
            graphics.fill(left + panelWidth - 4, thumbTop, left + panelWidth - 2, thumbTop + thumb, 0xFFAAAAAA);
        }
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void onClose() {
        // A click already sent the preset; ESC and the cancel button only drop the session.
        if (!sent) {
            PacketDistributor.sendToServer(new ChassisChoicePayload(token, ""));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
