package dimblend.experience.mixin.compat.gunsmith;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.gunsmith.GunsmithData;
import dimblend.experience.compat.gunsmith.GunsmithRuntime;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * gundb {@code GunDurability#fireEvent} 的耐久与配件损坏改写：
 *
 * <ol>
 *   <li><b>蓝色过度维修条先掉</b>：在 {@code ItemStack#setDamageValue} 处，只要存在
 *       {@code DimBlendOverDurability > 0} 就只递减蓝色耐久、不增加原版 {@code damageValue}，
 *       因此原枪械卡壳判定与低耐久衰减自动不受蓝条干扰；蓝条掉光后才扣原耐久。</li>
 *   <li><b>配件归零当场销毁</b>：捕获整轮配件扣耐久循环写回 {@code CUSTOM_DATA} 后的枪械引用，
 *       在 {@code fireEvent} 退出时统一扫描一次全槽位，所有已到 {@code maxDamage} 的配件
 *       当场卸载销毁（不退回背包、不漏掉循环后续槽位），并播放原版标准 {@code ITEM_BREAK} 音。</li>
 *   <li><b>历史存档 0 耐久配件兜底</b>：拦截 gundb 头部旧分支的 {@code Inventory#add} 返回
 *       {@code true}（不塞回背包、让 gundb 照常卸载同步），并将 {@code 2.5f / 0.6f} 音效替换为
 *       原版标准破裂音。</li>
 * </ol>
 */
@Pseudo
@Mixin(targets = "mod.cdv.gdb.GunDurability", remap = false)
public abstract class GunDurabilityFireMixin {

    @Unique
    private static final ThreadLocal<Player> DIMBLEND_SHOOTER = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<ItemStack> DIMBLEND_UPDATED_GUN = new ThreadLocal<>();

    @Inject(method = "fireEvent(Lcom/tacz/guns/api/event/common/GunFireEvent;)V",
            at = @At("HEAD"), require = 1, allow = 1)
    private static void dimblend$resetShotState(CallbackInfo ci) {
        DIMBLEND_SHOOTER.remove();
        DIMBLEND_UPDATED_GUN.remove();
    }

    @WrapOperation(
            method = "fireEvent(Lcom/tacz/guns/api/event/common/GunFireEvent;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;level()Lnet/minecraft/world/level/Level;"),
            require = 1,
            allow = 1)
    private static Level dimblend$captureShooter(Player player, Operation<Level> original) {
        DIMBLEND_SHOOTER.set(player);
        return original.call(player);
    }

    @WrapOperation(
            method = "fireEvent(Lcom/tacz/guns/api/event/common/GunFireEvent;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;setDamageValue(I)V"),
            require = 1,
            allow = 1)
    private static void dimblend$consumeOverDurability(ItemStack gun, int requestedDamage, Operation<Void> original) {
        if (GunsmithData.getOverDurability(gun) > 0) {
            GunsmithData.decrementOverDurability(gun);
            return;
        }
        original.call(gun, requestedDamage);
    }

    @WrapOperation(
            method = "fireEvent(Lcom/tacz/guns/api/event/common/GunFireEvent;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;set(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 2,
            allow = 2)
    private static Object dimblend$trackAttachmentWrite(
            ItemStack gun, DataComponentType<?> component, Object value, Operation<Object> original) {
        Object result = original.call(gun, component, value);
        if (component == DataComponents.CUSTOM_DATA) {
            DIMBLEND_UPDATED_GUN.set(gun);
        }
        return result;
    }

    @Inject(method = "fireEvent(Lcom/tacz/guns/api/event/common/GunFireEvent;)V",
            at = @At("RETURN"), require = 7, allow = 7)
    private static void dimblend$destroyBrokenAfterLoopAndCleanup(CallbackInfo ci) {
        Player shooter = DIMBLEND_SHOOTER.get();
        ItemStack updatedGun = DIMBLEND_UPDATED_GUN.get();
        DIMBLEND_SHOOTER.remove();
        DIMBLEND_UPDATED_GUN.remove();
        if (shooter != null && updatedGun != null) {
            GunsmithRuntime.destroyBrokenAttachments(shooter, updatedGun);
        }
    }

    @WrapOperation(
            method = "fireEvent(Lcom/tacz/guns/api/event/common/GunFireEvent;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"),
            require = 1,
            allow = 1)
    private static boolean dimblend$keepBrokenAttachmentOutOfInventory(
            Inventory inventory, ItemStack attachment, Operation<Boolean> original) {
        if (GunsmithRuntime.isBrokenAttachment(attachment)) {
            return true;
        }
        return original.call(inventory, attachment);
    }

    @WrapOperation(
            method = "fireEvent(Lcom/tacz/guns/api/event/common/GunFireEvent;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;playNotifySound(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
            require = 1,
            allow = 1)
    private static void dimblend$standardBreakSound(
            Player player, SoundEvent sound, SoundSource source, float volume, float pitch, Operation<Void> original) {
        if (sound == SoundEvents.ITEM_BREAK) {
            GunsmithRuntime.playBreakSound(player);
            return;
        }
        original.call(player, sound, source, volume, pitch);
    }
}
