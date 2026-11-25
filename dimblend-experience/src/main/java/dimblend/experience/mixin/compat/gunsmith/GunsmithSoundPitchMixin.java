package dimblend.experience.mixin.compat.gunsmith;

import dimblend.experience.compat.gunsmith.GunsmithAccess;
import dimblend.experience.compat.gunsmith.GunsmithData;
import dimblend.experience.compat.gunsmith.GunsmithMath;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 换弹与排障单音轨音效音高同步加速（仅客户端注册）：
 *
 * <ul>
 *   <li>{@code playReloadSound} 两处分支（空仓/战术换弹）：按
 *       {@link GunsmithMath#reloadSoundPitch(int)}（{@code 1.0x..1.5x}）缩放；</li>
 *   <li>{@code playInspectSound} 两处分支（空仓/有弹检视）：仅当主手枪械处于 {@code JAMMED}
 *       卡壳状态（gundb 路径 2 排障回退）时，按 {@link GunsmithMath#unjamSoundPitch(int)}
 *       （{@code 1.0x..2.0x}，OpenAL 上限防破音）缩放；普通检视不受影响；</li>
 *   <li>5 参 {@code playClientSound(Entity, ResourceLocation, float, float, int)}：覆盖 gundb
 *       {@code InspectKeybindHook} 路径 1 直接播放的 {@code "unjam"} 音效，按
 *       {@link GunsmithMath#unjamSoundPitch(int)} 缩放，并由 {@link #DIMBLEND_IN_SCALED_SOUND}
 *       防重入守卫杜绝与 {@code playReloadSound}/{@code playInspectSound} 二次叠加。</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.sound.SoundPlayManager", remap = false)
public abstract class GunsmithSoundPitchMixin {

    @Unique
    private static final ThreadLocal<Boolean> DIMBLEND_IN_SCALED_SOUND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "playReloadSound", at = @At("HEAD"), require = 1, allow = 1)
    private static void dimblend$enterReloadSound(CallbackInfo ci) {
        DIMBLEND_IN_SCALED_SOUND.set(Boolean.TRUE);
    }

    @Inject(method = "playReloadSound", at = @At("RETURN"), require = 1, allow = 1)
    private static void dimblend$exitReloadSound(CallbackInfo ci) {
        DIMBLEND_IN_SCALED_SOUND.set(Boolean.FALSE);
    }

    @ModifyArg(
            method = "playReloadSound",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/client/sound/SoundPlayManager;"
                            + "playClientSound(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/resources/ResourceLocation;FFI)"
                            + "Lcom/tacz/guns/client/sound/GunSoundInstance;"),
            index = 3,
            require = 2,
            allow = 2)
    private static float dimblend$reloadPitch(float pitch) {
        ItemStack held = dimblend$localGun();
        int level = GunsmithData.getLevel(held);
        if (level <= 0) {
            return pitch;
        }
        return pitch * GunsmithMath.reloadSoundPitch(level);
    }

    @Inject(method = "playInspectSound", at = @At("HEAD"), require = 1, allow = 1)
    private static void dimblend$enterInspectSound(CallbackInfo ci) {
        DIMBLEND_IN_SCALED_SOUND.set(Boolean.TRUE);
    }

    @Inject(method = "playInspectSound", at = @At("RETURN"), require = 1, allow = 1)
    private static void dimblend$exitInspectSound(CallbackInfo ci) {
        DIMBLEND_IN_SCALED_SOUND.set(Boolean.FALSE);
    }

    @ModifyArg(
            method = "playInspectSound",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/client/sound/SoundPlayManager;"
                            + "playClientSound(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/resources/ResourceLocation;FFI)"
                            + "Lcom/tacz/guns/client/sound/GunSoundInstance;"),
            index = 3,
            require = 2,
            allow = 2)
    private static float dimblend$inspectUnjamPitch(float pitch) {
        ItemStack held = dimblend$localGun();
        int level = GunsmithData.getLevel(held);
        if (level <= 0 || !GunsmithAccess.isJammed(held)) {
            return pitch;
        }
        return pitch * GunsmithMath.unjamSoundPitch(level);
    }

    @ModifyArgs(
            method = "playClientSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)Lcom/tacz/guns/client/sound/GunSoundInstance;",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/client/sound/SoundPlayManager;"
                            + "playClientSound(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/resources/ResourceLocation;FFIZ)"
                            + "Lcom/tacz/guns/client/sound/GunSoundInstance;"),
            require = 1,
            allow = 1)
    private static void dimblend$directUnjamSoundPitch(Args args) {
        if (Boolean.TRUE.equals(DIMBLEND_IN_SCALED_SOUND.get())) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        Entity entity = args.get(0);
        if (entity != minecraft.player) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        int level = GunsmithData.getLevel(held);
        if (level <= 0 || !GunsmithAccess.isJammed(held)) {
            return;
        }
        ResourceLocation soundName = args.get(1);
        if (GunsmithAccess.isUnjamSound(held, soundName)) {
            float pitch = args.get(3);
            args.set(3, pitch * GunsmithMath.unjamSoundPitch(level));
        }
    }

    @Unique
    private static ItemStack dimblend$localGun() {
        Minecraft minecraft = Minecraft.getInstance();
        return (minecraft == null || minecraft.player == null)
                ? ItemStack.EMPTY
                : minecraft.player.getMainHandItem();
    }
}
