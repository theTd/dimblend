package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.exploration.BedRestRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
    @Unique
    private boolean dimblend$bedRestWarmedUp;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/food/FoodData;tick(Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1, allow = 1)
    private void dimblend$restInsteadOfHungerTick(FoodData food, Player player, Operation<Void> original) {
        if (!BedRestRules.isResting(player)) {
            this.dimblend$bedRestTicks = 0;
            this.dimblend$bedRestWarmedUp = false;
            original.call(food, player);
            return;
        }
        int ticks = ++this.dimblend$bedRestTicks;
        if (!this.dimblend$bedRestWarmedUp && ticks >= BedRestRules.REST_WARMUP_TICKS) {
            // 预热完成后给一次 30 秒生命恢复 II（amplifier 1），本段睡眠不再重复给；
            // 血量交给效果自然结算，不做任何血量检测
            this.dimblend$bedRestWarmedUp = true;
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
                    BedRestRules.REST_REGENERATION_DURATION_TICKS, 1));
        }
        if (this.dimblend$bedRestWarmedUp
                && ticks % BedRestRules.HEART_PARTICLE_INTERVAL_TICKS == 0) {
            BedRestRules.showRestHearts(player);
        }
    }

    @Inject(method = "stopSleepInBed", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$resetRestOnWake(boolean wakeImmediately, boolean updateSleepingList, CallbackInfo ci) {
        this.dimblend$bedRestTicks = 0;
        this.dimblend$bedRestWarmedUp = false;
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
