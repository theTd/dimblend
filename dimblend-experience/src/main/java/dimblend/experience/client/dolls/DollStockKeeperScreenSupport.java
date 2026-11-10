package dimblend.experience.client.dolls;

import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import dimblend.experience.compat.dolls.DollKeeperSeatBlockEntity;
import dimblend.experience.compat.dolls.DollStockKeeperInteraction;
import net.minecraft.client.gui.GuiGraphics;

/** Client-only helpers stay outside the mixin declaration package. */
public final class DollStockKeeperScreenSupport {
    public static boolean hasKeeper(StockTickerBlockEntity ticker) {
        return !ticker.isRemoved() && ticker.getLevel() != null
                && DollStockKeeperInteraction.keeperFor(ticker.getLevel(), ticker.getBlockPos()) != null;
    }

    public static void renderKeeper(GuiGraphics graphics, StockTickerBlockEntity ticker, int left, int bottom) {
        if (ticker.isRemoved() || ticker.getLevel() == null) {
            return;
        }
        DollKeeperSeatBlockEntity keeper = DollStockKeeperInteraction.keeperFor(ticker.getLevel(), ticker.getBlockPos());
        if (keeper == null) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(left - 51, bottom - 63, 50);
        graphics.pose().scale(2, 2, 1);
        graphics.renderItem(keeper.doll(), 0, 0);
        graphics.pose().popPose();
    }

    private DollStockKeeperScreenSupport() {}
}
