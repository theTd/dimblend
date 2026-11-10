package dimblend.experience.mixin.compat.create.stockkeeper;

import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import dimblend.experience.compat.dolls.DollStockKeeperInteraction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's own keeper test only looks for an occupied SeatEntity (plus a heater). The upstream
 * proxy keeper is a block entity whose install removes every SeatEntity, so without this the
 * ticker reports "no keeper" and refuses the native category/request menu even though the doll
 * is installed. The doll is not a LivingEntity, so this cannot be solved by widening instanceof.
 *
 * Only the presence answer is widened; Create keeps its own permission, lock, shopping-list and
 * menu rules unchanged. Uniqueness/validity is the proxy keeper's existing rule.
 */
@Mixin(StockTickerBlockEntity.class)
public abstract class DollStockTickerBlockEntityMixin {
    @Inject(method = "isKeeperPresent", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$proxyKeeperIsPresent(CallbackInfoReturnable<Boolean> cir) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() == null || self.isRemoved()) {
            return;
        }
        if (DollStockKeeperInteraction.keeperFor(self.getLevel(), self.getBlockPos()) != null) {
            cir.setReturnValue(true);
        }
    }
}
