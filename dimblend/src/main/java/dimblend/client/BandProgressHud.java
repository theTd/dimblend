package dimblend.client;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Progress bar tracking how far the player has travelled through the current
 * dimblend band, rendered to the left of the vanilla hotbar and snapped to the
 * bottom of the screen: exactly as wide as the vanilla armor row (10 icon slots
 * at an 8px step plus the 9px sprite width of the last icon). It sits in the
 * bottom strip next to the hotbar with a small hotbar-style edge margin
 * (3px off the screen bottom, 6px off the hotbar), so it never overlaps the
 * armor/health rows. The band number is drawn centered on the bar.
 *
 * <p>Invoked from the wrapper around {@code VanillaGuiLayers.HOTBAR}
 * immediately after the vanilla hotbar renders, so the bar always tracks the
 * hotbar-anchored bottom area.
 */
public final class BandProgressHud {
    private static final int BAR_WIDTH = 81; // armor row: 10 icons * 8px step, last sprite 9px wide
    private static final int BAR_HEIGHT = 5;
    private static final int HOTBAR_HALF_WIDTH = 91; // vanilla hotbar is 182px wide, centered
    private static final int OFFHAND_SLOT_WIDTH = 29; // vanilla offhand slot parked left of the hotbar
    private static final int GAP_FROM_HOTBAR = 6;
    private static final int BOTTOM_MARGIN = 3;
    private static final int MIN_LEFT_MARGIN = 2;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final int FILL_COLOR = 0xFFE0A020;

    private BandProgressHud() {
    }

    /** Must be called right after the vanilla HOTBAR layer rendered this frame. */
    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        if (mc.level == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) {
            return;
        }
        if (!mc.level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return;
        }
        int bandSize = ClientBandProgress.bandSize();
        if (bandSize <= 0) {
            return;
        }
        Entity camera = mc.getCameraEntity();
        if (!(camera instanceof Player player)) {
            return;
        }

        int hotbarLeft = graphics.guiWidth() / 2 - HOTBAR_HALF_WIDTH;
        // Snap to the screen bottom with a small hotbar-style edge margin,
        // parked in the gap left of the hotbar. When the offhand slot is shown
        // it occupies the 29px directly left of the hotbar, so anchor further
        // left to avoid painting over it. Clamped so narrow windows never push
        // the bar off-screen.
        int anchorRight = hotbarLeft;
        if (!player.getOffhandItem().isEmpty()) {
            anchorRight -= OFFHAND_SLOT_WIDTH;
        }
        int left = Math.max(MIN_LEFT_MARGIN, anchorRight - GAP_FROM_HOTBAR - BAR_WIDTH);
        int barTop = graphics.guiHeight() - BOTTOM_MARGIN - BAR_HEIGHT;

        graphics.fill(left, barTop, left + BAR_WIDTH, barTop + BAR_HEIGHT, BACKGROUND_COLOR);
        int blockX = player.getBlockX();
        int region = BandLayout.regionOfBlockX(blockX, bandSize);
        float progress = Math.floorMod(blockX, bandSize) / (float) bandSize;
        int fillWidth = Math.round(progress * (BAR_WIDTH - 2));
        if (fillWidth > 0) {
            graphics.fill(left + 1, barTop + 1, left + 1 + fillWidth, barTop + BAR_HEIGHT - 1, FILL_COLOR);
        }
        int textY = barTop + (BAR_HEIGHT - mc.font.lineHeight) / 2;
        graphics.drawCenteredString(mc.font, Integer.toString(region), left + BAR_WIDTH / 2, textY, 0xFFFFFFFF);
    }
}
