package dimblend.experience.mixin.compat.gunsmith;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.gunsmith.GunsmithAccess;
import dimblend.experience.compat.gunsmith.GunsmithMath;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 枪托/刺刀/扳手近战（{@code ModernKineticGunItem#doMelee → doPerLivingHurt}）同步吃隐藏等级伤害倍率
 * （0–50 级线性 {@code 1.0x..2.0x}）。
 *
 * <p>{@code doPerLivingHurt} 是 {@code private static} 方法，体内只有一处
 * {@code LivingEntity#hurt(DamageSource, float)}（偏移 83），攻击者直接从
 * {@code source.getEntity()} 取得。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.item.ModernKineticGunItem", remap = false)
public abstract class GunsmithMeleeDamageMixin {

    @WrapOperation(
            method = "doPerLivingHurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            require = 1,
            allow = 1)
    private static boolean dimblend$scaleMeleeDamage(
            LivingEntity target, DamageSource source, float amount, Operation<Boolean> original) {
        Entity attacker = source == null ? null : source.getEntity();
        int level = GunsmithAccess.levelOfHeldGun(attacker, null);
        float scaled = level > 0 ? amount * GunsmithMath.damageMultiplier(level) : amount;
        return original.call(target, source, scaled);
    }
}
