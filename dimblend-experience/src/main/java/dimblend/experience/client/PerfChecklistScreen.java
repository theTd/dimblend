package dimblend.experience.client;

import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.perf.PerfCheck;
import dimblend.experience.tuning.perf.PerfCheckReportPayload;
import dimblend.experience.tuning.perf.PerfCheckRequestPayload;
import dimblend.experience.tuning.perf.PerfCheckResult;
import dimblend.experience.tuning.perf.PerfScope;
import dimblend.experience.tuning.perf.PerfStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Setup-wizard style performance checklist: one page per step (largest stall source first),
 * then a summary page whose rows jump back to their step. Server steps come from the server
 * ({@link PerfCheckReportPayload}); client steps are evaluated locally.
 */
public final class PerfChecklistScreen extends Screen {
    private static final String KEY = PerfCheck.PREFIX;
    private static final int SERVER_TIMEOUT_TICKS = 100;
    private static final int SUMMARY_ROW = 14;
    private final TuningScreen parent;
    private List<PerfCheckResult> serverResults;
    private List<PerfCheckResult> steps = List.of();
    private int page;
    private int waited;
    private boolean requested;
    private boolean serverTimedOut;
    private int summaryScroll;
    private Component status = Component.empty();
    private int left;
    private int panelWidth;
    private int contentTop;
    private int contentBottom;

    public PerfChecklistScreen(TuningScreen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
    }

    TuningScreen parent() { return parent; }

    @Override
    protected void init() {
        panelWidth = Math.min(520, width - 24);
        left = (width - panelWidth) / 2;
        contentTop = 40;
        contentBottom = height - 60;
        if (!requested) {
            requested = true;
            requestServer("");
        }
        rebuildSteps();
        int row1 = height - 52;
        int row2 = height - 26;
        if (onStepPage()) {
            PerfCheckResult step = steps.get(page);
            if (step.fixable()) {
                addRenderableWidget(Button.builder(Component.translatable(step.check().key() + ".fix"),
                        button -> fix(step)).bounds(left + 8, row1, 200, 20).build());
            } else if (step.check() == PerfCheck.SABLE_PHYSICS && step.status() != PerfStatus.UNAVAILABLE) {
                addRenderableWidget(Button.builder(Component.translatable(KEY + "open_sable"), button -> {
                    parent.showSection(TuningOption.Section.SABLE);
                    minecraft.setScreen(parent);
                }).bounds(left + 8, row1, 200, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.translatable(KEY + "recheck"), button -> recheck())
                .bounds(left + panelWidth - 108, row1, 100, 20).build());
        Button back = Button.builder(Component.translatable(KEY + "back"), button -> turn(-1))
                .bounds(width / 2 - 158, row2, 100, 20).build();
        back.active = page > 0;
        addRenderableWidget(back);
        Button next = Button.builder(Component.translatable(KEY + (page < steps.size() - 1 ? "next" : "to_summary")),
                button -> turn(1)).bounds(width / 2 - 50, row2, 100, 20).build();
        next.active = onStepPage();
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.translatable(KEY + "done"), button -> onClose())
                .bounds(width / 2 + 58, row2, 100, 20).build());
    }

    private boolean onStepPage() { return page < steps.size(); }

    private void rebuildSteps() {
        List<PerfCheckResult> merged = new ArrayList<>(ClientPerfChecks.collect(minecraft));
        if (serverResults != null) {
            merged.addAll(serverResults);
        }
        merged.sort(Comparator.comparing((PerfCheckResult result) -> result.check().ordinal())
                .thenComparing(PerfCheckResult::scope));
        steps = List.copyOf(merged);
        page = Math.min(page, steps.size());
    }

    private void requestServer(String fix) {
        if (minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(new PerfCheckRequestPayload(fix));
        }
    }

    private void fix(PerfCheckResult step) {
        if (step.scope() == PerfScope.SERVER) {
            status = Component.translatable(KEY + "message.applying");
            requestServer(step.check().id());
            return;
        }
        status = Component.translatable(KEY + (ClientPerfChecks.fix(minecraft, step.check())
                ? "message.fixed" : "message.fix_unavailable"));
        rebuildWidgets();
    }

    private void recheck() {
        status = Component.translatable(KEY + "message.checking");
        requestServer("");
        rebuildWidgets();
    }

    private void turn(int direction) {
        page = Math.max(0, Math.min(steps.size(), page + direction));
        summaryScroll = 0;
        rebuildWidgets();
    }

    public void receive(PerfCheckReportPayload report) {
        if ("no_permission".equals(report.message())) {
            serverTimedOut = true;
            status = Component.translatable(TuningOption.PREFIX + "no_permission");
            return;
        }
        serverResults = report.results();
        serverTimedOut = false;
        status = report.message().isEmpty() ? Component.empty() : Component.translatable(KEY + "message." + report.message());
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (serverResults == null && !serverTimedOut && ++waited > SERVER_TIMEOUT_TICKS) {
            serverTimedOut = true;
            status = Component.translatable(KEY + "message.no_server");
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!onStepPage() && button == 0 && mouseX >= left && mouseX <= left + panelWidth) {
            int index = ((int) mouseY - summaryTop() + summaryScroll) / SUMMARY_ROW;
            if (mouseY >= summaryTop() && mouseY <= contentBottom && index >= 0 && index < steps.size()) {
                page = index;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!onStepPage()) {
            int max = Math.max(0, steps.size() * SUMMARY_ROW - (contentBottom - summaryTop()));
            summaryScroll = Math.max(0, Math.min(max, summaryScroll - (int) (vertical * SUMMARY_ROW)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private int summaryTop() { return contentTop + 26; }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(left, contentTop - 4, left + panelWidth, contentBottom + 4, 0xE0181818);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        Component progress = onStepPage()
                ? Component.translatable(KEY + "step", page + 1, steps.size())
                : Component.translatable(KEY + "summary");
        graphics.drawCenteredString(font, progress, width / 2, 22, 0xFFAAAAAA);
        if (serverResults == null && !serverTimedOut) {
            graphics.drawCenteredString(font, Component.translatable(KEY + "message.checking"),
                    width / 2, contentTop + 4, 0xFFAAAAAA);
        }
        if (onStepPage()) {
            renderStep(graphics, steps.get(page));
        } else {
            renderSummary(graphics, mouseX, mouseY);
        }
        // Not super.render: in 1.21 it paints the menu background over the content drawn above.
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        var statusLines = font.split(status, panelWidth);
        for (int i = 0; i < Math.min(1, statusLines.size()); i++) {
            graphics.drawCenteredString(font, statusLines.get(i), width / 2, height - 64, 0xFFFFFFFF);
        }
    }

    private void renderStep(GuiGraphics graphics, PerfCheckResult step) {
        int x = left + 10;
        int y = contentTop + 16;
        int textWidth = panelWidth - 20;
        Object[] args = step.args().toArray();
        graphics.drawString(font, Component.translatable(step.check().key())
                .append(" · ").append(Component.translatable(step.scope().key())), x, y, 0xFFFFFFFF);
        y += 14;
        graphics.drawString(font, Component.literal("● ").append(Component.translatable(step.status().key())),
                x, y, step.status().color());
        y += 16;
        y = paragraph(graphics, label("current").append(Component.translatable(step.check().key() + ".current", args)),
                x, y, textWidth, 0xFFE0E0E0);
        y = paragraph(graphics, label("advice").append(Component.translatable(step.adviceKey(), args)),
                x, y + 4, textWidth, step.status().needsAttention() ? step.status().color() : 0xFFE0E0E0);
        paragraph(graphics, label("why").append(Component.translatable(step.check().key() + ".why")),
                x, y + 4, textWidth, 0xFFAAAAAA);
    }

    private MutableComponent label(String name) {
        return Component.translatable(KEY + "label." + name).append(" ");
    }

    private int paragraph(GuiGraphics graphics, Component text, int x, int y, int textWidth, int color) {
        List<FormattedCharSequence> lines = font.split(text, textWidth);
        for (FormattedCharSequence line : lines) {
            if (y + 9 > contentBottom) {
                break;
            }
            graphics.drawString(font, line, x, y, color);
            y += 10;
        }
        return y;
    }

    private void renderSummary(GuiGraphics graphics, int mouseX, int mouseY) {
        long open = steps.stream().filter(step -> step.status().needsAttention()).count();
        graphics.drawString(font, Component.translatable(KEY + "summary_intro", open, steps.size()),
                left + 10, contentTop + 8, 0xFFE0E0E0);
        graphics.enableScissor(left, summaryTop(), left + panelWidth, contentBottom);
        for (int i = 0; i < steps.size(); i++) {
            PerfCheckResult step = steps.get(i);
            int y = summaryTop() + i * SUMMARY_ROW - summaryScroll;
            boolean hovered = mouseX >= left && mouseX <= left + panelWidth && mouseY >= y && mouseY < y + SUMMARY_ROW;
            if (hovered) {
                graphics.fill(left + 4, y - 2, left + panelWidth - 4, y + SUMMARY_ROW - 2, 0x40FFFFFF);
            }
            graphics.drawString(font, Component.literal("● ").append(Component.translatable(step.status().key())),
                    left + 10, y, step.status().color());
            graphics.drawString(font, Component.translatable(step.check().key()).append(" · ")
                    .append(Component.translatable(step.scope().key())), left + 110, y, 0xFFFFFFFF);
        }
        graphics.disableScissor();
    }
}
