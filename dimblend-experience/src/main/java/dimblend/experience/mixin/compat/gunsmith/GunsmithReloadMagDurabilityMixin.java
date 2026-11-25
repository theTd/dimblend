package dimblend.experience.mixin.compat.gunsmith;

import dimblend.experience.compat.gunsmith.GunsmithAccess;
import dimblend.experience.compat.gunsmith.GunsmithRuntime;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修补 gundb {@code ModernKineticGunMixin} 在扩容弹匣（{@code EXTENDED_MAG}）换弹扣耐久上的两个缺陷
 * （<b>priority 900，先于 gundb 默认 1000</b>）：
 *
 * <ol>
 *   <li><b>写回丢失</b>：gundb 在 {@code defaultReloadFinishing} HEAD 只修改了
 *       {@code getAttachment} 返回的临时副本，从未写回枪械 {@code CUSTOM_DATA}。
 *       本 mixin 在 {@code TAIL} 将新损耗写回 {@code AttachmentEXTENDED_MAG.components."minecraft:damage"}。</li>
 *   <li><b>归零退回背包</b>：gundb 在 {@code defaultReloadFinishing} 与 {@code startReload}
 *       HEAD 会把 0 耐久扩容弹匣 {@code Inventory#add} 退回背包并播低沉音。
 *       本 mixin 抢先在 priority 900 的 HEAD 销毁已归零（或本轮即将归零）的扩容弹匣，
 *       使 gundb 随后读取到 {@code ItemStack.EMPTY} 而安全跳过。</li>
 * </ol>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.item.ModernKineticGunItem", priority = 900, remap = false)
public abstract class GunsmithReloadMagDurabilityMixin {

    @Inject(
            method = "startReload(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"),
            require = 1,
            allow = 1)
    private void dimblend$destroyAlreadyBrokenExtendedMagOnStart(
            @Coerce Object dataHolder, ItemStack gunItem, LivingEntity shooter,
            CallbackInfoReturnable<Boolean> cir) {
        GunsmithRuntime.breakExtendedMagIfDepleted(shooter, gunItem, false);
    }

    @Inject(
            method = "defaultReloadFinishing(Lcom/tacz/guns/item/ModernKineticGunScriptAPI;Z)V",
            at = @At("HEAD"),
            require = 1,
            allow = 1)
    private void dimblend$destroyDepletedExtendedMagBeforeGundb(
            @Coerce Object api, boolean isTactical, CallbackInfo ci) {
        LivingEntity shooter = GunsmithAccess.scriptApiShooter(api);
        ItemStack gun = GunsmithAccess.scriptApiItemStack(api);
        GunsmithRuntime.breakExtendedMagIfDepleted(shooter, gun, true);
    }

    @Inject(
            method = "defaultReloadFinishing(Lcom/tacz/guns/item/ModernKineticGunScriptAPI;Z)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1)
    private void dimblend$writeBackExtendedMagDurability(
            @Coerce Object api, boolean isTactical, CallbackInfo ci) {
        LivingEntity shooter = GunsmithAccess.scriptApiShooter(api);
        ItemStack gun = GunsmithAccess.scriptApiItemStack(api);
        GunsmithRuntime.damageExtendedMagOnReload(shooter, gun);
    }
}
