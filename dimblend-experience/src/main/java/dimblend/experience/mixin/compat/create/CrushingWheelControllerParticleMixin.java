package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.compat.sable.CrushingWheelParticleScope;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 标记 Sable 子层级上粉碎轮控制器空转时的 {@code CRIT} 粒子（覆盖原版与 Design n' Decor 粉碎轮共用控制器）。
 */
@Mixin(CrushingWheelControllerBlock.class)
public abstract class CrushingWheelControllerParticleMixin {

    @WrapMethod(
            method = "animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            require = 1,
            allow = 1)
    private void dimblend$scopeControllerAnimateParticles(
            BlockState state,
            Level level,
            BlockPos pos,
            RandomSource random,
            Operation<Void> original) {
        if (!level.isClientSide || Sable.HELPER.getContainingClient(pos) == null) {
            original.call(state, level, pos, random);
            return;
        }
        CrushingWheelParticleScope.enter();
        try {
            original.call(state, level, pos, random);
        } finally {
            CrushingWheelParticleScope.exit();
        }
    }
}
