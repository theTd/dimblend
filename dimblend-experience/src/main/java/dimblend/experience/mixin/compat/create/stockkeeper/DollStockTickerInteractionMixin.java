package dimblend.experience.mixin.compat.create.stockkeeper;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import dimblend.experience.compat.create.stockkeeper.DollStockKeeperCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(StockTickerInteractionHandler.class)
public abstract class DollStockTickerInteractionMixin {
    // The 6.0.10 INSTANCEOF has no subsequent LivingEntity cast. Keep all other original checks.
    @Definition(id = "LivingEntity", type = LivingEntity.class)
    @Expression("? instanceof LivingEntity")
    @ModifyExpressionValue(method = "getStockTickerPosition",
            at = @At("MIXINEXTRAS:EXPRESSION"),
            require = 1, allow = 1)
    private static boolean dimblend$acceptDoll(boolean living, Entity entity) {
        return living || DollStockKeeperCompat.isDoll(entity);
    }

    @WrapOperation(method = "interactWithLogisticsManager",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/logistics/stockTicker/StockTickerInteractionHandler;interactWithLogisticsManagerAt(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"),
            require = 1, allow = 1)
    private static boolean dimblend$squeakAfterMenuOpened(Player player, Level level, BlockPos tickerPos,
            Operation<Boolean> original, PlayerInteractEvent.EntityInteractSpecific event) {
        AbstractContainerMenu previousMenu = player.containerMenu;
        boolean handled = original.call(player, level, tickerPos);
        DollStockKeeperCompat.afterInteraction(event, tickerPos, previousMenu);
        return handled;
    }
}