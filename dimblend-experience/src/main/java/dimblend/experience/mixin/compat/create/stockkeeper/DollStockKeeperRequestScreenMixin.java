package dimblend.experience.mixin.compat.create.stockkeeper;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import dimblend.experience.client.dolls.DollStockKeeperScreenSupport;
import dimblend.experience.compat.create.stockkeeper.DollStockKeeperCompat;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single client mixin for both keeper models after this merge: the upstream proxy keeper
 * block entity (vanilla doll item installed on a keeper seat variant) and the real
 * kaleidoscope_doll:doll entity seated on an ordinary Create seat.
 *
 * The close suppression accepts either model. Create's containerTick closes the container when
 * no LivingEntity keeper and no blaze burner is present, so covering only one model would close
 * the other. A doll Entity must never be stored in Create's WeakReference&lt;LivingEntity&gt;
 * fields: init, containerTick and renderBg all cast that result.
 */
@Mixin(StockKeeperRequestScreen.class)
public abstract class DollStockKeeperRequestScreenMixin extends AbstractContainerScreen<StockKeeperRequestMenu> {
    @Shadow private StockTickerBlockEntity blockEntity;

    @Unique private WeakReference<Entity> dimblend$doll = new WeakReference<>(null);
    @Unique private WeakReference<Entity> dimblend$seat = new WeakReference<>(null);
    @Unique private BlockPos dimblend$tickerPos;

    private DollStockKeeperRequestScreenMixin(StockKeeperRequestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // Create 6.0.10 returns early for a blaze burner as well as at the end of the constructor.
    // TAIL only collects the final return; both paths must look for a doll.
    @Inject(method = "<init>", at = @At("RETURN"), require = 2, allow = 2)
    private void dimblend$findDoll(StockKeeperRequestMenu menu, Inventory inventory, Component title, CallbackInfo ci) {
        Object holder = menu.contentHolder;
        if (!(holder instanceof StockTickerBlockEntity ticker)) return;
        Entity doll = DollStockKeeperCompat.findKeeper(ticker);
        if (doll == null) return;
        dimblend$doll = new WeakReference<>(doll);
        dimblend$seat = new WeakReference<>(doll.getVehicle());
        dimblend$tickerPos = ticker.getBlockPos().immutable();
    }

    @WrapOperation(method = "containerTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;closeContainer()V"),
            require = 1, allow = 1)
    private void dimblend$retainKeeperMenu(Player player, Operation<Void> original) {
        if (dimblend$hasProxyKeeper() || dimblend$hasValidDoll()) {
            return;
        }
        original.call(player);
    }

    /** Upstream proxy keeper preview: the installed doll item drawn at 2x in the keeper corner. */
    @Inject(method = "renderBg", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$renderProxyKeeper(GuiGraphics graphics, float partialTick, int mouseX, int mouseY,
            CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().screen) {
            DollStockKeeperScreenSupport.renderKeeper(graphics, blockEntity, leftPos, topPos + imageHeight);
        }
    }

    @Unique
    private boolean dimblend$hasProxyKeeper() {
        return blockEntity != null && DollStockKeeperScreenSupport.hasKeeper(blockEntity);
    }

    @Unique
    private boolean dimblend$hasValidDoll() {
        return dimblend$tickerPos != null
                && DollStockKeeperCompat.isValidKeeper(dimblend$doll.get(), dimblend$seat.get(), dimblend$tickerPos);
    }
}
