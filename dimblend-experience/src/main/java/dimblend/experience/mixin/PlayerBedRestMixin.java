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

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/food/FoodData;tick(Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1, allow = 1)
    private void dimblend$restInsteadOfHungerTick(FoodData food, Player player, Operation<Void> original) {
        if (!BedRestRules.isResting(player)) {
            this.dimblend$bedRestTicks = 0;
            original.call(food, player);
            return;
        }
        int ticks = ++this.dimblend$bedRestTicks;
        if (BedRestRules.grantsRegenerationOnTick(ticks)) {
            // 躺下第 1 tick 就给一次生命恢复 X（amplifier 9 → 界面显示等级 10），
            // 时长 8 秒与自动起身对齐；血量交给效果自然结算，不做任何血量检测。
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
                    BedRestRules.REST_REGENERATION_DURATION_TICKS,
                    BedRestRules.REST_REGENERATION_AMPLIFIER));
        }
        if (BedRestRules.emitsHeartsOnTick(ticks)) {
            BedRestRules.showRestHearts(player);
        }
        if (BedRestRules.autoWakesOnTick(ticks)) {
            // 服务端权威：满 8 秒让玩家起身（原版黎明起床同一路径，客户端收到动画包后退出床界面）。
            player.stopSleepInBed(false, true);
        }
    }

    @Inject(method = "stopSleepInBed", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$resetRestOnWake(boolean wakeImmediately, boolean updateSleepingList, CallbackInfo ci) {
        // 起身即复位：下一次入睡必须重新计时、重新给一次回血。
        this.dimblend$bedRestTicks = 0;
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
