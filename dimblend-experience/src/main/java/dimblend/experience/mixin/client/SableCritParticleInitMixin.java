package dimblend.experience.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.compat.sable.CrushingWheelParticleScope;
import net.minecraft.client.particle.CritParticle;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link CritParticle} 在构造器末尾直接调用 {@code this.tick()}，此时 {@code ParticleEngine#add}
 * 尚未执行 {@code sable$initialKickOut()}，粒子坐标仍处于 {@code ~1.6e7} 的 plot 局部空间。
 *
 * <p>对于 Sable 粉碎轮生成的暴击粒子，构造阶段先把 {@code trackingSubLevel} 暂空，让这次预热的
 * {@code tick()} 在纯局部坐标下步进，随后由 {@code ParticleEngine#add} 一次性把 {@code xo} 与
 * {@code x} 同步投影到世界空间，杜绝列车位移重复叠加与偏航角乘以 {@code 1.6e7} 的飞散。</p>
 */
@Mixin(CritParticle.class)
public abstract class SableCritParticleInitMixin {

    @WrapOperation(
            method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/CritParticle;tick()V"),
            require = 1,
            allow = 1)
    private void dimblend$localTickBeforeInitialKickOut(CritParticle instance, Operation<Void> original) {
        if (!CrushingWheelParticleScope.isActive()
                || !(instance instanceof ParticleExtension extension)) {
            original.call(instance);
            return;
        }
        SubLevel tracked = extension.sable$getTrackingSubLevel();
        if (!(tracked instanceof ClientSubLevel clientSubLevel)) {
            original.call(instance);
            return;
        }
        extension.sable$setTrackingSubLevel(null, Vec3.ZERO);
        try {
            original.call(instance);
        } finally {
            extension.sable$setTrackingSubLevel(
                    clientSubLevel, instance.getBoundingBox().getCenter());
        }
    }
}
