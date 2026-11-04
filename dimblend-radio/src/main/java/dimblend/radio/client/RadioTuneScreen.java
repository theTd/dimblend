package dimblend.radio.client;

import dimblend.radio.RadioVolumeRule;
import dimblend.radio.net.RadioTunePayloads;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The radio tuning screen a player opens by right-clicking a jukebox that is playing a station.
 *
 * <p><b>Why it lives in {@code dimblend-radio}.</b> These knobs belong to this mod's audio, and
 * {@code dimblend-experience} depends on this module — not the other way round — so the screen
 * cannot be reused from there. It is written against plain vanilla widgets on purpose: the old
 * panel's Create/catnip widgets would drag a dependency into the audio mod for a layout.
 *
 * <p><b>Why the values are not edited locally.</b> Each press sends one edit and waits for the
 * server's reply, which redraws the screen. The alternative — optimistically showing the local
 * value — would show a number the server never accepted, and the radio would keep playing at the
 * old one with nothing on screen saying so.
 */
public final class RadioTuneScreen extends Screen {
    private static final String KEY = "screen.dimblend_radio.tune.";
    private static final int PANEL_MAX_WIDTH = 320;
    private static final int ROW_HEIGHT = 24;

    /** The global knob labels, in {@link RadioTunePayloads#GLOBAL_OPTIONS} order. */
    private static final String[] GLOBAL_KEYS = {
            KEY + "reception_noise", KEY + "end_voice_frequency",
            KEY + "end_voice_volume", KEY + "acoustic_intensity"
    };
    /** Each global knob's step, matching {@code RadioTuning}'s own validation. */
    private static final double[] GLOBAL_STEPS = {0.01, 0.1, 0.01, 0.01};
    /** Each global knob's ceiling, matching {@code RadioTuning}'s own validation. */
    private static final double[] GLOBAL_MAX = {2.0, 4.0, 2.0, 2.0};

    private final BlockPos pos;
    private final int volumePercent;
    private final double[] globals;
    private final boolean editable;
    private final String message;
    private int left;
    private int panelWidth;
    private int top;

    private RadioTuneScreen(RadioTunePayloads.Snapshot snapshot) {
        super(Component.translatable(KEY + "title"));
        this.pos = snapshot.pos();
        this.volumePercent = snapshot.volumePercent();
        this.globals = snapshot.globals().clone();
        this.editable = snapshot.editable();
        this.message = snapshot.message();
    }

    /**
     * Opens or refreshes the screen for a snapshot.
     *
     * <p>A snapshot that arrives while the screen is already open is applied in place, not by
     * opening a second screen: two screens would stack, and closing one would reveal a stale
     * copy underneath.
     */
    public static void receive(RadioTunePayloads.Snapshot snapshot) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (!snapshot.message().isEmpty()) {
            // A refusal or a save report: say it on the chat bar, where the player will see it even
            // if the screen failed to open at all.
            minecraft.gui.setNowPlaying(Component.translatable(KEY + snapshot.message()));
        }
        minecraft.setScreen(new RadioTuneScreen(snapshot));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_MAX_WIDTH, width - 24);
        left = (width - panelWidth) / 2;
        top = 40;
        int y = top;

        // The per-jukebox volume: the one value this screen exists for.
        addRowButtons(y, KEY + "volume", volumeLabel(), () -> stepVolume(-1), () -> stepVolume(1),
                () -> sendVolume(volumePercent));
        y += ROW_HEIGHT * 2;

        for (int i = 0; i < GLOBAL_KEYS.length; i++) {
            final int index = i;
            addRowButtons(y, GLOBAL_KEYS[i], globalLabel(i),
                    () -> stepGlobal(index, -1), () -> stepGlobal(index, 1),
                    () -> sendGlobal(index, globals[index]));
            y += ROW_HEIGHT * 2;
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left + panelWidth / 2 - 50, y + 4, 100, 20).build());
    }

    /** One labelled row with -, value, + and reset. */
    private void addRowButtons(int y, String labelKey, Component value,
            Runnable decrease, Runnable increase, Runnable reset) {
        addRenderableWidget(Button.builder(Component.literal("-"), button -> decrease.run())
                .bounds(left + 8, y, 20, 20).build()).active = editable;
        addRenderableWidget(Button.builder(value, button -> reset.run())
                .bounds(left + 32, y, panelWidth - 64, 20)
                .tooltip(Tooltip.create(Component.translatable(labelKey))).build()).active = editable;
        addRenderableWidget(Button.builder(Component.literal("+"), button -> increase.run())
                .bounds(left + panelWidth - 28, y, 20, 20).build()).active = editable;
    }

    private Component volumeLabel() {
        if (volumePercent == RadioVolumeRule.DYNAMIC) {
            return Component.translatable(KEY + "volume.dynamic");
        }
        return Component.literal(volumePercent + "%");
    }

    private Component globalLabel(int index) {
        return Component.literal(String.format(Locale.ROOT, "%.2f", globals[index]));
    }

    /**
     * Steps the per-jukebox volume.
     *
     * <p>0 is "dynamic" and sits between the top of the fixed range and the bottom when wrapping,
     * so the player can always get back to it with the arrows rather than having to know it is
     * special. The order therefore runs 150 down to 10, then 0.
     */
    private void stepVolume(int direction) {
        int next;
        if (direction > 0) {
            next = volumePercent >= RadioVolumeRule.MAX ? RadioVolumeRule.DYNAMIC : volumePercent + RadioVolumeRule.STEP;
        } else {
            next = volumePercent <= RadioVolumeRule.DYNAMIC ? RadioVolumeRule.MAX : volumePercent - RadioVolumeRule.STEP;
        }
        if (next == RadioVolumeRule.DYNAMIC) {
            next = RadioVolumeRule.DYNAMIC;
        }
        send(new RadioTunePayloads.Edit(pos, RadioTunePayloads.VOLUME_OPTION, next));
    }

    private void sendVolume(int value) {
        send(new RadioTunePayloads.Edit(pos, RadioTunePayloads.VOLUME_OPTION, value));
    }

    private void stepGlobal(int index, int direction) {
        double step = GLOBAL_STEPS[index];
        double next = globals[index] + direction * step;
        if (next > GLOBAL_MAX[index]) {
            next = 0.0;
        } else if (next < 0.0) {
            next = GLOBAL_MAX[index];
        }
        sendGlobal(index, next);
    }

    private void sendGlobal(int index, double value) {
        send(new RadioTunePayloads.Edit(pos, RadioTunePayloads.GLOBAL_OPTIONS[index], value));
    }

    private void send(RadioTunePayloads.Edit edit) {
        PacketDistributor.sendToServer(edit);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(left, top - 8, left + panelWidth, top + ROW_HEIGHT * 2 * 5 + 40, 0xE0181818);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
        // The block position is shown so a player standing between two jukeboxes can tell which
        // one this screen belongs to.
        graphics.drawCenteredString(font, Component.literal(pos.toShortString()), width / 2, 28, 0xFFAAAAAA);
        int y = top;
        drawLabel(graphics, KEY + "volume_hint", y);
        y += ROW_HEIGHT * 2;
        for (String key : GLOBAL_KEYS) {
            drawLabel(graphics, key, y);
            y += ROW_HEIGHT * 2;
        }
        if (!editable) {
            graphics.drawCenteredString(font, Component.translatable(KEY + "read_only"),
                    width / 2, height - 30, 0xFFFF5555);
        }
        if (!message.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(KEY + message), width / 2, height - 44, 0xFFAAAAAA);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws one row's label above its buttons, which the widget list draws. */
    private void drawLabel(GuiGraphics graphics, String key, int y) {
        graphics.drawString(font, Component.translatable(key), left + 8, y - 10, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}