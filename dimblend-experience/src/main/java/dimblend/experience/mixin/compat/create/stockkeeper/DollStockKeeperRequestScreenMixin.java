package dimblend.experience.mixin.compat.create.stockkeeper;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import dimblend.experience.compat.create.stockkeeper.DollStockKeeperCompat;
import java.lang.ref.WeakReference;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not put Entity into Create's WeakReference<LivingEntity>: init/render/tick all cast its result. */
@Mixin(StockKeeperRequestScreen.class)
public abstract class DollStockKeeperRequestScreenMixin {
    @Unique private WeakReference<Entity> dimblend$doll = new WeakReference<>(null);
    @Unique private WeakReference<Entity> dimblend$seat = new WeakReference<>(null);
    @Unique private BlockPos dimblend$tickerPos;

    // Create 6.0.10 returns early for a blaze burner as well as at the end of the constructor.
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
    private void dimblend$retainDollMenu(Player player, Operation<Void> original) {
        if (dimblend$tickerPos == null
                || !DollStockKeeperCompat.isValidKeeper(dimblend$doll.get(), dimblend$seat.get(), dimblend$tickerPos)) {
            original.call(player);
        }
    }
}