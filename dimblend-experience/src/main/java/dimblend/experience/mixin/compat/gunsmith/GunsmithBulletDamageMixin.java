package dimblend.experience.mixin.compat.gunsmith;

import dimblend.experience.compat.gunsmith.GunsmithData;
import dimblend.experience.compat.gunsmith.GunsmithMath;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TaCZ 子弹隐藏伤害加成（0–50 级线性 {@code 1.0x..2.0x}，绝不写入 {@code AttachmentCacheProperty}，
 * 因而不在任何 UI/Tooltip 泄露）。
 *
 * <p>在受保护的 10 参构造器 {@code TAIL}（此时 {@code this} 已完成初始化且
 * {@code this.explosionDamage} 已算完）从 {@code gunItem} 读取等级倍率并缓存到
 * {@link #dimblend$damageMultiplier}，同时缩放爆炸伤害 {@code explosionDamage}；
 * 命中阶段在 {@code getDamage(Vec3)} 返回处乘算倍率（自动等比覆盖常规弹、霰弹每颗弹丸、
 * 距离衰减及爆头基数）。</p>
 *
 * <p>反序列化构造器 {@code (EntityType, Level)} 不受影响，避免客户端同步时对
 * {@code explosionDamage} 二次乘算。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.entity.EntityKineticBullet", remap = false)
public abstract class GunsmithBulletDamageMixin {

    @Unique
    private float dimblend$damageMultiplier = 1.0f;

    @Shadow private float explosionDamage;

    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;ZLcom/tacz/guns/resource/pojo/data/gun/GunData;Lcom/tacz/guns/resource/pojo/data/gun/BulletData;)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1)
    private void dimblend$applyGunLevel(
            EntityType<?> type,
            Level worldIn,
            LivingEntity throwerIn,
            ItemStack gunItem,
            ResourceLocation ammoId,
            ResourceLocation gunId,
            ResourceLocation gunDisplayId,
            boolean isTracerAmmo,
            @Coerce Object gunData,
            @Coerce Object bulletData,
            CallbackInfo ci) {
        int level = GunsmithData.getLevel(gunItem);
        if (level <= 0) {
            return;
        }
        float multiplier = GunsmithMath.damageMultiplier(level);
        this.dimblend$damageMultiplier = multiplier;
        if (this.explosionDamage > 0.0f) {
            this.explosionDamage *= multiplier;
        }
    }

    @Inject(method = "getDamage(Lnet/minecraft/world/phys/Vec3;)F",
            at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$scaleImpactDamage(Vec3 hitPosition, CallbackInfoReturnable<Float> cir) {
        float base = cir.getReturnValueF();
        if (base > 0.0f && this.dimblend$damageMultiplier > 1.0f) {
            cir.setReturnValue(base * this.dimblend$damageMultiplier);
        }
    }
}
