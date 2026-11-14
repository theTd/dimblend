package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.compat.create.CrushingParticleMath;
import dimblend.experience.compat.sable.CrushingWheelParticleScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 标记 Sable 子层级上粉碎轮控制器空转时的 {@code CRIT} 粒子（覆盖原版与 Design n' Decor 粉碎轮共用控制器），
 * 并把 plot 坐标系里被 {@code float} 吞掉的抖动还原成 {@code double} 口径。
 *
 * <p>Create 6.0.10 的 {@code animateTick} 字节码是
 * {@code pos.getX() → i2f → rand.nextFloat() → fadd → f2d}。plot 方块坐标从 {@code 20,480,000} 起
 * （已越过 {@code 2^24}，{@code float} ULP = 2.0），于是整数坐标先被挪最多 1 格、子格抖动再被整体舍掉，
 * 粒子固定落在格角上——正是玩家报的「大石磨轮粒子位置固定偏移，挪动轮子才换地方」。
 * 判据与数值见 {@link CrushingParticleMath}。</p>
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
            if (CrushingParticleMath.needsExactDouble(pos.getX(), pos.getY(), pos.getZ())) {
                this.dimblend$spawnExactCritParticles(state, level, pos, random);
                return;
            }
            original.call(state, level, pos, random);
        } finally {
            CrushingWheelParticleScope.exit();
        }
    }

    /**
     * {@code CrushingWheelControllerBlock#animateTick} 的逐句复刻（Create 6.0.10 字节码 {@code 0..93}），
     * 只把坐标那一步换成 {@link CrushingParticleMath#exactJitteredCoordinate}。
     *
     * <p>随机数消耗必须与 Create 逐次一致：{@code nextInt(1)}（恒 0，但消耗一次 LCG）后紧跟三次
     * {@code nextFloat()}；否则载具上其它使用 {@code Level.random} 的粒子会整体错位。</p>
     */
    @Unique
    private void dimblend$spawnExactCritParticles(
            BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(CrushingWheelControllerBlock.VALID)) {
            return;
        }
        if (random.nextInt(1) != 0) {
            return;
        }
        level.addParticle(
                ParticleTypes.CRIT,
                CrushingParticleMath.exactJitteredCoordinate(pos.getX(), random.nextFloat()),
                CrushingParticleMath.exactJitteredCoordinate(pos.getY(), random.nextFloat()),
                CrushingParticleMath.exactJitteredCoordinate(pos.getZ(), random.nextFloat()),
                0.0D, 0.0D, 0.0D);
    }
}
