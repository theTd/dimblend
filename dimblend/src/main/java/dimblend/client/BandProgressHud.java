package dimblend.client;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/**
 * Progress bar tracking how far the player has travelled through the current
 * dimblend band, rendered to the left of the vanilla hotbar and snapped to the
 * bottom of the screen: exactly as wide as the vanilla armor row (10 icon slots
 * at an 8px step plus the 9px sprite width of the last icon). It sits in the
 * bottom strip next to the hotbar with a small hotbar-style edge margin
 * (3px off the screen bottom, 6px off the hotbar), so it never overlaps the
 * armor/health rows. While the player is on the track corridor (|z| &lt; 16),
 * two centered lines sit right above the bar: "编号-当前区域名" over
 * "前方到达：…" (random-drawn neighbors show 未知; names come from
 * {@code BandLaneSync} pushes, mapped by {@link BandLaneNames}); off the
 * corridor the lines hide. Wherever the player is, a white vertical marker
 * pins the frontmost on-track Sable's centroid (see {@link SableTrainLocator})
 * so the player can see where the train is; when the train is in another band
 * the marker clamps to the bar edge on the train's side.
 *
 * <p>Visible in survival, adventure and creative; hidden in spectator
 * (vanilla {@code canHurtPlayer} is survival/adventure-only, so the gate
 * explicitly excludes spectator instead).
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
    private static final int TRAIN_MARKER_COLOR = 0xFFFFFFFF;

    private BandProgressHud() {
    }

    /** Must be called right after the vanilla HOTBAR layer rendered this frame. */
    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        if (mc.level == null || mc.gameMode == null) {
            return;
        }
        // 创造也显示；旁观隐藏。
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
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

        // 区段序号只出现在上方文字行里，不再重复画在进度条上。
        if (Math.abs(player.getBlockZ()) < SableTrainLocator.TRACK_HALF_WIDTH) {
            // 区域名两行居中挂在进度条正上方：上 = 当前「编号-区域名」，下 = 「前方到达：…」；
            // 前方区域为随机抽选（或服务端尚未推送）时显示「未知」
            int centerX = left + BAR_WIDTH / 2;
            String nextLane = ClientBandLane.nextLane();
            String nextText = "前方到达：" + (nextLane.isEmpty() ? "未知" : BandLaneNames.display(nextLane));
            int nextY = barTop - 2 - mc.font.lineHeight;
            graphics.drawCenteredString(mc.font, region + "-" + BandLaneNames.display(ClientBandLane.lane()),
                    centerX, nextY - mc.font.lineHeight, 0xFFFFFFFF);
            graphics.drawCenteredString(mc.font, nextText, centerX, nextY, 0xFFFFFFFF);
        }
        // 火车标记不分是否在轨道走廊内都画：沿轨道步行找车时同样需要。
        drawTrainMarker(graphics, mc, left, barTop, bandSize, region);
    }

    /**
     * 1px 白色竖杠，标出最前轨道上 Sable 质心的位置；火车不在玩家当前区段时
     * 钉在指向火车那一侧的边缘，提示往回走的方向。
     */
    private static void drawTrainMarker(GuiGraphics graphics, Minecraft mc, int left, int barTop,
            int bandSize, int region) {
        OptionalDouble trainX = SableTrainLocator.frontmostCenterX(mc.level);
        if (trainX.isEmpty()) {
            return;
        }
        int trainBlockX = (int) Math.floor(trainX.getAsDouble());
        int trainRegion = BandLayout.regionOfBlockX(trainBlockX, bandSize);
        int markerX;
        if (trainRegion == region) {
            float trainProgress = Math.floorMod(trainBlockX, bandSize) / (float) bandSize;
            markerX = left + 1 + Math.round(trainProgress * (BAR_WIDTH - 2));
        } else {
            markerX = trainRegion < region ? left : left + BAR_WIDTH - 1;
        }
        graphics.fill(markerX, barTop - 1, markerX + 1, barTop + BAR_HEIGHT + 1, TRAIN_MARKER_COLOR);
    }
}
