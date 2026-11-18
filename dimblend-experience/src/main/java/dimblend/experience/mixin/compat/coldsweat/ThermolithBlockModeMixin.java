package dimblend.experience.mixin.compat.coldsweat;

import com.momosoftworks.coldsweat.common.block.ThermolithBlock;
import dimblend.experience.compat.coldsweat.ThermolithModeRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 {@code cold_sweat:thermolith} 的方块状态补 {@code dimblend_mode}（normal/ice/magma）
 * 与 {@code dimblend_suppressed}（邻近外源红石信号时置 true）。属性对象在
 * {@link ThermolithModeRules} 里，本 mixin 只做 add 与客户端粒子。
 *
 * <p>TAIL 注入而不是 {@code @Overwrite}：原版 {@code createBlockStateDefinition} 里那两条
 * （FACING、POWERED）保持 Cold Sweat 自己的写法，任何版本调整都无需同步本文件。</p>
 *
 * <p>红色粉尘是纯客户端的 {@code animateTick} 附加：抑制时 {@code POWERED} 必为 false，
 * Cold Sweat 自己的绿粒子根本不会喷，所以这里只加不覆盖。位置不跟随朝向——需求是
 * "方块四周冒红粉"，绕一圈采样比复刻朝向偏移更容易读懂。粒子等级过滤交给客户端的
 * {@code ParticleEngine}（{@code addParticle} 自身会按 "最少粒子" 设置丢弃）。</p>
 */
@Mixin(value = ThermolithBlock.class, remap = false)
public abstract class ThermolithBlockModeMixin {

    @Inject(method = "createBlockStateDefinition(Lnet/minecraft/world/level/block/state/StateDefinition$Builder;)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$addModeProperty(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(ThermolithModeRules.MODE);
        builder.add(ThermolithModeRules.DIMBLEND_SUPPRESSED);
    }

    @Inject(
            method = "animateTick(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"),
            require = 1,
            allow = 1
    )
    private void dimblend$redParticlesWhenSuppressed(BlockState state, Level level, BlockPos pos,
            RandomSource random, CallbackInfo ci) {
        if (!state.hasProperty(ThermolithModeRules.DIMBLEND_SUPPRESSED)
                || !state.getValue(ThermolithModeRules.DIMBLEND_SUPPRESSED)) {
            return;
        }
        int count = 2 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            double x = pos.getX() + 0.15 + random.nextDouble() * 0.7;
            double y = pos.getY() + 0.15 + random.nextDouble() * 0.8;
            double z = pos.getZ() + 0.15 + random.nextDouble() * 0.7;
            DustParticleOptions particle = new DustParticleOptions(
                    DustParticleOptions.REDSTONE_PARTICLE_COLOR, random.nextFloat() * 0.5F + 0.5F);
            level.addParticle(particle, x, y, z, 0.0, 0.0, 0.0);
        }
    }
}
