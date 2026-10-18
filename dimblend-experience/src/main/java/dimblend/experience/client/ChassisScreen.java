package dimblend.experience.client;

import java.util.UUID;
import dimblend.experience.chassis.ChassisBlueprint;
import dimblend.experience.chassis.ChassisChoicePayload;
import dimblend.experience.chassis.ChassisScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** A server-acknowledged choice followed by a non-pausing cooling-off period. */
public final class ChassisScreen extends Screen {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 210;
    private final UUID token;
    private final Button[] choices = new Button[2];
    private int selected = -1;
    private long readyAt;
    private boolean pending;

    private ChassisScreen(UUID token) {
        super(Component.translatable("screen.dimblend_experience.chassis"));
        this.token = token;
    }

    public static void apply(ChassisScreenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.selected() == -1) {
            minecraft.setScreen(new ChassisScreen(payload.token()));
        } else if (minecraft.screen instanceof ChassisScreen screen && screen.token.equals(payload.token())) {
            if (payload.selected() == -2) { minecraft.setScreen(null); return; }
            screen.selected = payload.selected();
            screen.readyAt = System.nanoTime() + Math.max(0, payload.waitMillis()) * 1_000_000L;
            screen.pending = false;
        }
    }

    @Override protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        for (int i = 0; i < choices.length; i++) {
            int kind = i;
            choices[i] = addRenderableWidget(Button.builder(Component.translatable(
                    ChassisBlueprint.values()[i].translationKey()), button -> choose(kind))
                    .bounds(left + 16, top + 65 + i * 72, WIDTH - 32, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 110, top + HEIGHT - 30, 100, 20).build());
        updateButtons();
    }

    private void choose(int kind) {
        pending = true;
        PacketDistributor.sendToServer(new ChassisChoicePayload(token, kind, selected >= 0));
        updateButtons();
    }

    @Override public void tick() { updateButtons(); }

    private void updateButtons() {
        boolean ready = selected >= 0 && System.nanoTime() >= readyAt;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == null) continue;
            choices[i].active = !pending && (selected == -1 || (selected == i && ready));
            choices[i].setMessage(selected == i ? Component.translatable(ready
                    ? "screen.dimblend_experience.chassis.confirm" : "screen.dimblend_experience.chassis.irreversible")
                    : Component.translatable(ChassisBlueprint.values()[i].translationKey()));
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xF0101010);
        graphics.renderOutline(left, top, WIDTH, HEIGHT, 0xFF8B8B8B);
        graphics.drawCenteredString(font, title, width / 2, top + 10, 0xFFFFFFFF);
        for (int i = 0; i < 2; i++) {
            graphics.drawWordWrap(font, Component.translatable(ChassisBlueprint.values()[i].translationKey() + ".description"),
                    left + 16, top + 31 + i * 72, WIDTH - 32, 0xFFDDDDDD);
        }
        for (var renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() {
        PacketDistributor.sendToServer(new ChassisChoicePayload(token, -1, false));
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
}
