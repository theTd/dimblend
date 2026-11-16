package dimblend.experience.mixin.compat.tavern;

import dimblend.experience.compat.tavern.TavernBrewingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Barrel ingredient handler slot limit. Targets the anonymous {@code ItemStackHandler} subclass
 * so the visible slot capacity follows the configured economy (4 by default, 16 in quarter mode).
 */
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity$1",
        remap = false)
public abstract class BarrelIngredientHandlerMixin {

    @Inject(method = "getSlotLimit(I)I", at = @At("HEAD"), cancellable = true,
            remap = false, require = 1, allow = 1)
    private void dimblend$slotLimit(int slot, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(TavernBrewingRules.slotLimit());
    }
}
