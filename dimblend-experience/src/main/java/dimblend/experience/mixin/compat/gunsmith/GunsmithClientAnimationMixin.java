package dimblend.experience.mixin.compat.gunsmith;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.gunsmith.GunsmithAnimations;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * TaCZ 客户端骨骼动画与关键帧音效同步加速（仅客户端注册）：
 *
 * <ul>
 *   <li>换弹动画（含 {@code reload}）：{@code 1.0x..1.5x}，与服务端 {@code getReloadTime()} 对齐；</li>
 *   <li>排障动画（{@code unjam} 排除 {@code unjam_idle}，以及枪械 {@code JAMMED} 时回退播的
 *       {@code inspect}/{@code inspect_empty}）：{@code 1.0x..3.0x}，与路径 1 的 {@code UnjamPacket}
 *       及路径 2 的 {@code StartJamWorkerPacket} 对齐；</li>
 *   <li>同步缩放 {@code update(Z)} 中的过渡混合时间 {@code transitionProgressNs}，以及
 *       {@code update(Z)} / {@code updateSoundOnly()} 里关键帧音效的 {@code pitch}（参数 5）。</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.api.client.animation.ObjectAnimationRunner", remap = false)
public abstract class GunsmithClientAnimationMixin {

    @Shadow private long transitionProgressNs;

    @ModifyVariable(method = "updateProgress(J)V", at = @At("HEAD"), argsOnly = true,
            require = 1, allow = 1)
    private long dimblend$scaleAnimationDelta(long delta) {
        if (delta <= 0L) {
            return delta;
        }
        float multiplier = GunsmithAnimations.speedMultiplier(this);
        if (multiplier <= 1.0f) {
            return delta;
        }
        return Math.max(1L, Math.round(delta * (double) multiplier));
    }

    @WrapOperation(
            method = "update(Z)V",
            at = @At(value = "FIELD",
                    target = "Lcom/tacz/guns/api/client/animation/ObjectAnimationRunner;transitionProgressNs:J",
                    opcode = Opcodes.PUTFIELD),
            require = 1,
            allow = 1)
    private void dimblend$scaleTransitionDelta(@Coerce Object instance, long newValue, Operation<Void> original) {
        long delta = newValue - this.transitionProgressNs;
        if (delta > 0L) {
            float multiplier = GunsmithAnimations.transitionSpeedMultiplier(this);
            if (multiplier > 1.0f) {
                original.call(instance, this.transitionProgressNs + Math.max(1L, Math.round(delta * (double) multiplier)));
                return;
            }
        }
        original.call(instance, newValue);
    }

    @ModifyArg(
            method = "update(Z)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/api/client/animation/ObjectAnimationSoundChannel;"
                            + "playSound(DDLnet/minecraft/world/entity/Entity;IFF)V"),
            index = 5,
            require = 1,
            allow = 1)
    private float dimblend$scaleKeyframeSoundPitchUpdate(float pitch) {
        return pitch * GunsmithAnimations.soundPitchMultiplier(this);
    }

    @ModifyArg(
            method = "updateSoundOnly()V",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/api/client/animation/ObjectAnimationSoundChannel;"
                            + "playSound(DDLnet/minecraft/world/entity/Entity;IFF)V"),
            index = 5,
            require = 1,
            allow = 1)
    private float dimblend$scaleKeyframeSoundPitchSoundOnly(float pitch) {
        return pitch * GunsmithAnimations.soundPitchMultiplier(this);
    }
}
