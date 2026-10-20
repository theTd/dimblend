package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.exploration.BedRestRules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pause hunger and its natural regeneration while a player rests in bed. */
@Mixin(Player.class)
public abstract class PlayerBedRestMixin {
    @Unique
    private int dimblend$bedRestTicks;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/food/FoodData;tick(Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1, allow = 1)
    private void dimblend$restInsteadOfHungerTick(FoodData food, Player player, Operation<Void> original) {
        if (!BedRestRules.isResting(player)) {
            this.dimblend$bedRestTicks = 0;
            original.call(food, player);
            return;
        }
        if (++this.dimblend$bedRestTicks >= BedRestRules.HEAL_INTERVAL_TICKS) {
            this.dimblend$bedRestTicks = 0;
            player.heal(BedRestRules.HEAL_AMOUNT);
        }
    }

    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$pauseRestingExhaustion(float exhaustion, CallbackInfo ci) {
        if (BedRestRules.isResting((Player) (Object) this)) ci.cancel();
    }

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"), require = 1, allow = 1)
    private void dimblend$replacePeacefulRegeneration(Player player, float amount, Operation<Void> original) {
        if (!BedRestRules.isResting(player)) original.call(player, amount);
    }
}
