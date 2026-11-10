package dimblend.experience.mixin.client;

import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import dimblend.experience.client.dolls.DollStockKeeperScreenSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StockKeeperRequestScreen.class, remap = false)
public abstract class DollStockKeeperRequestScreenMixin extends AbstractContainerScreen<StockKeeperRequestMenu> {
    @Shadow private StockTickerBlockEntity blockEntity;

    private DollStockKeeperRequestScreenMixin(StockKeeperRequestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Redirect(method = "containerTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;closeContainer()V", remap = true), require = 1, allow = 1)
    private void dimblend$keepDollMenuOpen(Player player) {
        if (!DollStockKeeperScreenSupport.hasKeeper(blockEntity)) {
            player.closeContainer();
        }
    }

    @Inject(method = "renderBg", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$renderActualDoll(GuiGraphics graphics, float partialTick, int mouseX, int mouseY,
            CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().screen) {
            DollStockKeeperScreenSupport.renderKeeper(graphics, blockEntity, leftPos, topPos + imageHeight);
        }
    }
}
