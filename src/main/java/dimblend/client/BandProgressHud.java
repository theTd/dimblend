package dimblend.client;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Progress bar tracking how far the player has travelled through the current
 * dimblend band, rendered in the armor HUD's column: exactly as wide as the
 * vanilla armor row (10 icon slots at an 8px step plus the 9px sprite width of
 * the last icon). With armor worn it sits directly above the armor icons; with
 * no armor it occupies the armor row's own slot. The band number is drawn
 * centered on the bar.
 *
 * <p>Invoked from the wrapper around {@code VanillaGuiLayers.ARMOR_LEVEL}
 * immediately after the vanilla armor layer renders, so the {@code Gui.leftHeight}
 * read below is the exact value the armor row just used — no cross-layer drift.
 */
public final class BandProgressHud {
    private static final int BAR_WIDTH = 81; // armor row: 10 icons * 8px step, last sprite 9px wide
    private static final int BAR_HEIGHT = 5;
    private static final int GAP_ABOVE_ARMOR = 1;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final int FILL_COLOR = 0xFFE0A020;

    private BandProgressHud() {
    }

    /** Must be called right after the vanilla ARMOR_LEVEL layer rendered this frame. */
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

        int armorValue = player.getArmorValue();
        // With armor drawn, the armor layer already added +10 to leftHeight and its
        // icons sit at guiHeight - leftHeight + 10; without armor nothing was drawn
        // and the would-be row slot starts at guiHeight - leftHeight.
        int slotTop = graphics.guiHeight() - mc.gui.leftHeight + (armorValue > 0 ? 10 : 0);
        int barTop = armorValue > 0 ? slotTop - GAP_ABOVE_ARMOR - BAR_HEIGHT : slotTop;

        int left = graphics.guiWidth() / 2 - 91;
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
