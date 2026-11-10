package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dimblend.experience.exploration.BackpackTotemProtection;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTotemDeathMixin {
    @ModifyExpressionValue(method = "die", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/CommonHooks;onLivingDeath(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)Z"),
            require = 1, allow = 1)
    private boolean dimblend$protectAcceptedDeath(boolean cancelled) {
        if (!cancelled) BackpackTotemProtection.begin((ServerPlayer) (Object) this);
        return cancelled;
    }

    @Inject(method = "restoreFrom", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$clearConsumedProtection(ServerPlayer original, boolean keepEverything, CallbackInfo ci) {
        BackpackTotemProtection.finishClone(original, (ServerPlayer) (Object) this, keepEverything);
    }
}
