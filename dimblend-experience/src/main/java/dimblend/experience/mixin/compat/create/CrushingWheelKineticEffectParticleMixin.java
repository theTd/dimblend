package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.compat.sable.CrushingWheelParticleScope;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 标记 Sable 子层级上粉碎轮本体过载/恢复时的烟雾与云团粒子（仅限 {@link CrushingWheelBlockEntity}）。
 */
@Mixin(KineticEffectHandler.class)
public abstract class CrushingWheelKineticEffectParticleMixin {

    @Shadow(remap = false)
    private KineticBlockEntity kte;

    @WrapMethod(
            method = "spawnEffect(Lnet/minecraft/core/particles/ParticleOptions;FI)V",
            require = 1,
            allow = 1)
    private void dimblend$scopeWheelEffectParticles(
            ParticleOptions particle, float maxMotion, int amount, Operation<Void> original) {
        Level world = this.kte != null ? this.kte.getLevel() : null;
        if (!(this.kte instanceof CrushingWheelBlockEntity)
                || world == null
                || !world.isClientSide
                || Sable.HELPER.getContainingClient(this.kte.getBlockPos()) == null) {
            original.call(particle, maxMotion, amount);
            return;
        }
        CrushingWheelParticleScope.enter();
        try {
            original.call(particle, maxMotion, amount);
        } finally {
            CrushingWheelParticleScope.exit();
        }
    }
}
