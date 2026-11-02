package dimblend.experience.client;

import dimblend.spyglass.api.SourceCatalog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Source toggle panel, opened from {@link TuningScreen}'s navigation row. It shows one checkbox per
 * namespace that resolved at least one whitelisted structure; the server owns the data and the
 * permission check, this class only displays and forwards clicks.
 *
 * <p>The list scrolls inside a scissor box, and a click outside that box never reaches a checkbox
 * whose row only happens to be there unscrolled.
 */
public final class SpyglassSourceScreen extends Screen {
    private static final String KEY = "screen.dimblend_experience.spyglass_sources";
    private static final int ROW_HEIGHT = 24;

    private final TuningScreen parent;
    private final List<Checkbox> boxes = new ArrayList<>();
    private List<SourceCatalog.Source> sources = List.of();
    private boolean editable = true;
    private String message = "";
    private int scroll;
    private int left;
    private int panelWidth;
    private int listTop;
    private int listBottom;
    private Button done;

    public SpyglassSourceScreen(TuningScreen parent) {
        super(Component.translatable(KEY + ".title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(520, width - 24);
        left = (width - panelWidth) / 2;
        listTop = 56;
        listBottom = height - 56;
        done = addRenderableWidget(Button.builder(Component.translatable(KEY + ".done"), button -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
        boxes.clear();
        rebuildRows();
        if (SpyglassTuningBridge.isLoaded()) {
            SpyglassTuningBridge.requestCatalog(this::acceptCatalog);
        }
    }

    private void acceptCatalog(SourceCatalog catalog) {
        sources = catalog.sources();
        editable = catalog.editable();
        message = catalog.message();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        rebuildRows();
    }

    private void rebuildRows() {
        for (Checkbox box : boxes) {
            removeWidget(box);
        }
        boxes.clear();
        for (int i = 0; i < sources.size(); i++) {
            SourceCatalog.Source source = sources.get(i);
            Checkbox box = Checkbox.builder(Component.literal(source.displayName()), font)
                    .pos(left + 10, rowY(i))
                    .maxWidth(panelWidth - 24)
                    .selected(source.enabled())
                    .onValueChange((checkbox, value) ->
                            SpyglassTuningBridge.setSourceEnabled(source.namespace(), value))
                    .build();
            box.active = editable;
            addRenderableWidget(box);
            boxes.add(box);
        }
    }

    private int rowY(int index) {
        return listTop + index * ROW_HEIGHT - scroll;
    }

    private int maxScroll() {
        return Math.max(0, sources.size() * ROW_HEIGHT - (listBottom - listTop));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        graphics.drawString(font, Component.translatable(KEY + ".hint"), left + 10, 36, 0xA0A0A0);
        if (sources.isEmpty()) {
            graphics.drawCenteredString(
                    font, Component.translatable(KEY + ".empty"), width / 2, listTop + 8, 0xA0A0A0);
        } else {
            graphics.enableScissor(left, listTop, left + panelWidth, listBottom);
            for (Checkbox box : boxes) {
                if (box.getY() + box.getHeight() >= listTop && box.getY() <= listBottom) {
                    box.render(graphics, mouseX, mouseY, partialTick);
                }
            }
            graphics.disableScissor();
        }
        done.render(graphics, mouseX, mouseY, partialTick);
        if (!message.isEmpty()) {
            graphics.drawCenteredString(
                    font, Component.translatable(KEY + ".status." + message), width / 2, height - 46, 0xFFFF55);
        } else if (!editable) {
            graphics.drawCenteredString(
                    font, Component.translatable(KEY + ".readonly"), width / 2, height - 46, 0xFF8080);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = maxScroll();
        if (max <= 0) {
            return false;
        }
        int next = Mth.clamp(scroll - (int) Math.round(scrollY * ROW_HEIGHT), 0, max);
        if (next == scroll) {
            return true;
        }
        scroll = next;
        for (int i = 0; i < boxes.size(); i++) {
            boxes.get(i).setY(rowY(i));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (done.isMouseOver(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (mouseY < listTop || mouseY > listBottom) {
            // A row scrolled out of the box keeps its widget position; do not let it take the click.
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        // The receiver must not keep a dead Screen; the server reply is dropped when it is cleared.
        SpyglassTuningBridge.clearCatalogReceiver();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
