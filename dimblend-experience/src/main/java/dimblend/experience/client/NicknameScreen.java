package dimblend.experience.client;

import java.util.List;

import dimblend.experience.nickname.ClientNicknames;
import dimblend.experience.nickname.NicknameEditPayload;
import dimblend.experience.nickname.NicknameStore;
import dimblend.experience.nickname.NicknameTarget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A8 物品昵称对话框。不暂停游戏。确定 / 回车提交新昵称，清除走同一条 C2S，
 * 取消或 Esc 关闭。空昵称留在对话框里提示，不发往服务端。
 */
public final class NicknameScreen extends Screen {

    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 112;
    private static final String KEY = "screen.dimblend_experience.nickname";

    private EditBox nameBox;
    private Component targetLine = Component.empty();
    private Component errorLine = Component.empty();
    private String draft = "";
    private boolean primed;

    public NicknameScreen() {
        super(Component.translatable(KEY));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        this.targetLine = describeTarget();

        String initial = this.primed ? this.draft : currentNickname();
        this.primed = true;

        this.nameBox = new EditBox(this.font, left + 16, top + 48, 248, 20,
                Component.translatable(KEY + ".hint"));
        this.nameBox.setMaxLength(NicknameStore.MAX_LENGTH);
        this.nameBox.setHint(Component.translatable(KEY + ".hint"));
        this.nameBox.setResponder(value -> {
            this.draft = value;
            this.errorLine = Component.empty();
        });
        this.nameBox.setValue(initial);
        if (!initial.isEmpty()) {
            this.nameBox.setHighlightPos(0);
        }
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);

        int buttonY = top + 76;
        int buttonX = left + 16;
        this.addRenderableWidget(Button.builder(Component.translatable(KEY + ".confirm"), button -> this.confirm())
                .bounds(buttonX, buttonY, 70, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable(KEY + ".clear"), button -> this.clearNickname())
                .bounds(buttonX + 74, buttonY, 100, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable(KEY + ".cancel"), button -> this.onClose())
                .bounds(buttonX + 178, buttonY, 70, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderTransparentBackground(graphics);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF0101010);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF8B8B8B);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 8, 0xFFFFFFFF);
        this.drawClipped(graphics, this.targetLine, top + 24, 0xFFDDDDDD);
        this.drawClipped(graphics, this.errorLine, top + 36, 0xFFFFFFFF);
        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // 输入框不吃回车；焦点在按钮上时 super 已经触发该按钮。
        if (keyCode == 257 || keyCode == 335) {
            this.confirm();
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void confirm() {
        String name = this.nameBox.getValue().trim();
        if (name.isEmpty()) {
            this.errorLine = Component.translatable("message.dimblend_experience.nickname.empty");
            return;
        }
        this.send(new NicknameEditPayload(false, name));
    }

    private void clearNickname() {
        this.send(new NicknameEditPayload(true, ""));
    }

    private void send(NicknameEditPayload payload) {
        if (this.minecraft.getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(payload);
        this.onClose();
    }

    private Component describeTarget() {
        if (this.minecraft.player == null) {
            return Component.translatable(KEY + ".no_target");
        }
        NicknameTarget.Result target = NicknameTarget.resolve(this.minecraft.player);
        if (target.status() == NicknameTarget.Status.FOUND && target.item() != null) {
            return Component.translatable(KEY + ".target", Component.translatable(target.item().getDescriptionId()));
        }
        if (target.status() == NicknameTarget.Status.NO_ITEM_FORM) {
            return Component.translatable(KEY + ".no_item_form");
        }
        return Component.translatable(KEY + ".no_target");
    }

    private String currentNickname() {
        if (this.minecraft.player == null) {
            return "";
        }
        NicknameTarget.Result target = NicknameTarget.resolve(this.minecraft.player);
        if (target.itemId() == null) {
            return "";
        }
        return ClientNicknames.get(target.itemId());
    }

    private void drawClipped(GuiGraphics graphics, Component text, int y, int color) {
        if (text.getString().isEmpty()) {
            return;
        }
        List<FormattedCharSequence> lines = this.font.split(text, PANEL_WIDTH - 16);
        if (!lines.isEmpty()) {
            graphics.drawCenteredString(this.font, lines.get(0), this.width / 2, y, color);
        }
    }
}
