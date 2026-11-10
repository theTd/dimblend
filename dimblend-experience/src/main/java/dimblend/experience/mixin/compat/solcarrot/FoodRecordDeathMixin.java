package dimblend.experience.mixin.compat.solcarrot;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dimblend.experience.exploration.BackpackTotemProtection;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep S.O.L's own clone/sync pipeline; override only this death's reset decision. */
@Mixin(targets = "com.cazsius.solcarrot.tracking.CapabilityHandler", remap = false)
public abstract class FoodRecordDeathMixin {
    @ModifyExpressionValue(method = "onClone", at = @At(value = "INVOKE",
            target = "Lcom/cazsius/solcarrot/SOLCarrotConfig;shouldResetOnDeath()Z"),
            require = 1, allow = 1)
    private static boolean dimblend$keepProtectedRecord(boolean reset, PlayerEvent.Clone event) {
        return reset && !BackpackTotemProtection.active(event.getOriginal());
    }
}
