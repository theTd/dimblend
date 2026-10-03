package dimblend.experience.client;

import dimblend.experience.tuning.TuningEditPayload;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSnapshotPayload;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TuningScreen extends Screen {
    private static final String KEY = TuningOption.PREFIX;
    private final Map<TuningOption, String> drafts = new EnumMap<>(TuningOption.class);
    private final Map<TuningOption, Double> pending = new LinkedHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private TuningSnapshotPayload snapshot;
    private TuningOption inFlight;
    private double inFlightValue;
    private int sendDelay;
    private int age;
    private int sentAt;
    private int scroll;
    private int contentHeight;
    private int left;
    private int panelWidth;
    private int listTop;
    private int listBottom;
    private Component status = Component.empty();
    private boolean updating;
    private boolean draggingScrollbar;

    public TuningScreen(TuningSnapshotPayload snapshot) {
        super(Component.translatable("screen.dimblend_experience.tuning"));
        this.snapshot = snapshot;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(520, width - 24);
        left = (width - panelWidth) / 2;
        listTop = 34;
        listBottom = height - 60;
        rows.clear();
        contentHeight = 0;
        updating = true;
        for (TuningOption option : TuningOption.values()) {
            boolean editable = snapshot.editable() && snapshot.available(option);
            int labelWidth = panelWidth - (option.isCheckbox() ? 98 : 176);
            var label = font.split(Component.translatable(option.key()), Math.max(40, labelWidth));
            Component description = Component.translatable(option.key() + ".description");
            if (!snapshot.available(option)) {
                description = description.copy().append(" ").append(Component.translatable(KEY + "missing_mod", option.modId()));
            }
            var lines = font.split(description, panelWidth - 20);
            int topHeight = Math.max(22, label.size() * 10);
            Row row = new Row(option, contentHeight, topHeight + 6 + lines.size() * 10 + 10,
                    topHeight, label, lines);
            rows.add(row);
            int y = listTop + contentHeight - scroll;
            if (option.isCheckbox()) {
                Checkbox checkbox = Checkbox.builder(Component.translatable(option.key()), font)
                        .maxWidth(labelWidth).pos(left + 8, y)
                        .selected(displayValue(option) == 1.0D)
                        .onValueChange((box, selected) -> {
                            if (!updating) {
                                queue(option, selected ? 1.0D : 0.0D, false);
                            }
                        }).build();
                row.checkbox = checkbox;
                add(row, checkbox, editable);
            } else {
                EditBox box = new EditBox(font, left + panelWidth - 142, y, 58, 20,
                        Component.translatable(option.key()));
                box.setMaxLength(12);
                box.setValue(drafts.getOrDefault(option, option.format(snapshot.value(option))));
                box.setResponder(text -> changed(option, text));
                row.input = box;
                add(row, box, editable);
                add(row, Button.builder(Component.literal("-"), button -> step(option, -1))
                        .bounds(left + panelWidth - 166, y, 20, 20)
                        .tooltip(Tooltip.create(Component.translatable(KEY + "decrease"))).build(), editable);
                add(row, Button.builder(Component.literal("+"), button -> step(option, 1))
                        .bounds(left + panelWidth - 80, y, 20, 20)
                        .tooltip(Tooltip.create(Component.translatable(KEY + "increase"))).build(), editable);
            }
            add(row, Button.builder(Component.translatable("controls.reset"), button -> reset(option))
                    .bounds(left + panelWidth - 56, y, 48, 20)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "reset_default"))).build(), editable);
            contentHeight += row.height;
        }
        updating = false;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        positionRows();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    private void add(Row row, AbstractWidget widget, boolean editable) {
        widget.active = editable;
        row.widgets.add(widget);
        addRenderableWidget(widget);
    }

    private double displayValue(TuningOption option) {
        if (pending.containsKey(option)) {
            return pending.get(option);
        }
        if (option == inFlight) {
            return inFlightValue;
        }
        return snapshot.value(option);
    }

    private void changed(TuningOption option, String text) {
        if (updating) {
            return;
        }
        drafts.put(option, text);
        try {
            double value = Double.parseDouble(text);
            if (option.isValid(value)) {
                queue(option, value, true);
                input(option).setTextColor(0xFFE0E0E0);
                return;
            }
        } catch (NumberFormatException ignored) {
        }
        pending.remove(option);
        input(option).setTextColor(0xFFFF7777);
        status = Component.translatable(KEY + "invalid");
    }

    private EditBox input(TuningOption option) {
        return rows.stream().filter(row -> row.option == option).findFirst().orElseThrow().input;
    }

    private void queue(TuningOption option, double value, boolean debounce) {
        pending.put(option, value);
        sendDelay = debounce ? 6 : 0;
        status = Component.translatable(KEY + "saving");
    }

    private void step(TuningOption option, int direction) {
        double value = displayValue(option);
        try {
            value = Double.parseDouble(input(option).getValue());
        } catch (NumberFormatException ignored) {
        }
        value = Math.max(0.0D, Math.min(option.maximum(), Math.rint(value / option.step() + direction) * option.step()));
        input(option).setValue(option.format(value));
        sendDelay = 0;
    }

    private void reset(TuningOption option) {
        if (option.isCheckbox()) {
            queue(option, option.defaultValue(), false);
            updating = true;
            Row row = rows.stream().filter(candidate -> candidate.option == option).findFirst().orElseThrow();
            if (row.checkbox.selected() != (option.defaultValue() == 1.0D)) {
                row.checkbox.onPress();
            }
            updating = false;
        } else {
            input(option).setValue(option.format(option.defaultValue()));
            sendDelay = 0;
        }
    }

    @Override
    public void tick() {
        age++;
        if (inFlight != null && age - sentAt > 100) {
            inFlight = null;
            status = Component.translatable(KEY + "timeout");
        }
        if (sendDelay > 0) {
            sendDelay--;
        } else if (inFlight == null && !pending.isEmpty() && minecraft.getConnection() != null) {
            var entry = pending.entrySet().iterator().next();
            inFlight = entry.getKey();
            inFlightValue = entry.getValue();
            sentAt = age;
            PacketDistributor.sendToServer(new TuningEditPayload(entry.getKey().id(), entry.getValue()));
            pending.remove(entry.getKey());
        }
    }

    public void receive(TuningSnapshotPayload next) {
        snapshot = next;
        if (inFlight != null && inFlight.id().equals(next.acknowledged())) {
            TuningOption acknowledged = inFlight;
            inFlight = null;
            if (!pending.containsKey(acknowledged)) {
                drafts.remove(acknowledged);
            }
        }
        if (!next.message().isEmpty()) {
            status = Component.translatable(KEY + next.message());
        }
        updating = true;
        for (Row row : rows) {
            row.widgets.forEach(widget -> widget.active = next.editable() && next.available(row.option));
            if (pending.containsKey(row.option) || row.option == inFlight) {
                continue;
            }
            if (row.checkbox != null && row.checkbox.selected() != (next.value(row.option) == 1.0D)) {
                row.checkbox.onPress();
            }
            if (row.input != null && !row.input.isFocused()) {
                row.input.setValue(row.option.format(next.value(row.option)));
                drafts.remove(row.option);
            }
        }
        updating = false;
    }

    private int maxScroll() { return Math.max(0, contentHeight - (listBottom - listTop)); }

    private void positionRows() {
        for (Row row : rows) {
            int y = listTop + row.offset - scroll;
            for (AbstractWidget widget : row.widgets) {
                widget.setY(y);
                widget.visible = y >= listTop && y + widget.getHeight() <= listBottom;
                if (!widget.visible) {
                    widget.setFocused(false);
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY >= listTop && mouseY <= listBottom) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (vertical * 30)));
            positionRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && maxScroll() > 0 && mouseX >= left + panelWidth - 6
                && mouseX <= left + panelWidth && mouseY >= listTop && mouseY <= listBottom) {
            draggingScrollbar = true;
            dragScrollbar(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingScrollbar && button == 0) {
            dragScrollbar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragScrollbar(double mouseY) {
        int track = listBottom - listTop;
        int thumb = Math.max(16, track * track / contentHeight);
        scroll = Math.max(0, Math.min(maxScroll(),
                (int) Math.round((mouseY - listTop - thumb / 2.0D) * maxScroll() / (track - thumb))));
        positionRows();
    }

    @Override
    public void onClose() {
        // Flush validated drafts on close; invalid partial input is never transmitted.
        if (minecraft.getConnection() != null) {
            pending.forEach((option, value) -> PacketDistributor.sendToServer(new TuningEditPayload(option.id(), value)));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(left, listTop - 4, left + panelWidth, listBottom + 4, 0xE0181818);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);
        graphics.enableScissor(left, listTop, left + panelWidth, listBottom);
        for (Row row : rows) {
            int y = listTop + row.offset - scroll;
            int labelX = left + (row.option.isCheckbox() ? 32 : 8);
            if (!row.option.isCheckbox()) {
                for (int i = 0; i < row.label.size(); i++) {
                    graphics.drawString(font, row.label.get(i), labelX, y + 4 + i * 10, 0xFFFFFFFF);
                }
            }
            for (int i = 0; i < row.description.size(); i++) {
                graphics.drawString(font, row.description.get(i), left + 8, y + row.topHeight + 4 + i * 10, 0xFFAAAAAA);
            }
            graphics.fill(left + 8, y + row.height - 4, left + panelWidth - 8, y + row.height - 3, 0xFF383838);
        }
        graphics.disableScissor();
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        if (maxScroll() > 0) {
            int track = listBottom - listTop;
            int thumb = Math.max(16, track * track / contentHeight);
            int thumbTop = listTop + scroll * (track - thumb) / maxScroll();
            graphics.fill(left + panelWidth - 3, thumbTop, left + panelWidth, thumbTop + thumb, 0xFFAAAAAA);
        }
        var statusLines = font.split(status, panelWidth);
        for (int i = 0; i < Math.min(2, statusLines.size()); i++) {
            graphics.drawCenteredString(font, statusLines.get(i), width / 2, height - 52 + i * 10, 0xFFFFFFFF);
        }
    }

    private static final class Row {
        final TuningOption option;
        final int offset;
        final int height;
        final int topHeight;
        final List<FormattedCharSequence> label;
        final List<FormattedCharSequence> description;
        final List<AbstractWidget> widgets = new ArrayList<>();
        EditBox input;
        Checkbox checkbox;

        Row(TuningOption option, int offset, int height, int topHeight,
            List<FormattedCharSequence> label, List<FormattedCharSequence> description) {
            this.option = option;
            this.offset = offset;
            this.height = height;
            this.topHeight = topHeight;
            this.label = label;
            this.description = description;
        }
    }
}
