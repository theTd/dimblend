package dimblend.experience.mixin.compat.yat;

import dev.minhnh.yetanotherthirst.compat.ThirstCompat;
import dimblend.experience.exploration.BedRestRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuse YAT's suspension policy for both its tick and thirsty-effect exhaustion. */
@Mixin(value = ThirstCompat.class, remap = false)
public abstract class SleepThirstSuspensionMixin {
    @Inject(method = "suspendsThirst", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void dimblend$suspendThirstDuringRest(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Player player && BedRestRules.isResting(player)) cir.setReturnValue(true);
    }
}
