package dimblend.experience.compat.cca;

import dimblend.experience.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

/**
 * D7 电动马达过载锁存（纯服务端、无状态 helper；锁存字段在
 * {@code ElectricMotorMixin} 内 @Unique 持有：不写 NBT，区块卸载/重进按新进入重判）。
 *
 * <p>进入：kinetic 网络过载（{@code isOverStressed()}）且 |面板|&gt;64rpm
 * 且运转中（active 且理论转速非零）。进入瞬间记录红石强度并冻结输出转速，
 * 期间一切外部更改（信号增减/撤除、面板扳手、CC setRPM）直接丢弃。</p>
 *
 * <p>锁存期：耗电按实时 rate×2（调用点翻倍，见 mixin MEV；motorSpeed 已冻结
 * 故数值恒定，无需快照）；服务端每 tick 在方块中心播 enchanted_hit
 * （count=5、delta=0、speed=0.5），每 20 tick 播一次 motor_overstress（音量 0.5，
 * 与 D4 叠加共存）。退出：过载恢复或 active=false（FE 耗尽断电）任一即清锁存。</p>
 *
 * <p>客户端显示（护目镜×2）不读锁存标志：该标志纯服务端内存，不进同步包；
 * 客户端用已同步状态派生等价判据（过载 + |面板|&gt;64 + 理论转速非零，
 * 见 {@code ElectricMotorGoggleMixin}）——overStressed 经 Network 标签同步、
 * 面板经 ScrollValueBehaviour 同步、理论转速经 Speed 标签同步，三者客户端全可见。</p>
 */
public final class MotorOverstressLatch {
    /** D7 门限：与 D4 共用 |面板|&gt;64rpm 口径。 */
    public static final float PANEL_THRESHOLD_RPM = 64.0F;
    /** 过载音间隔：20 tick = 1 秒（素材长约 0.99 秒，近无缝衔接）。 */
    public static final int SOUND_INTERVAL_TICKS = 20;
    /** 过载音量：对齐 D4 减半口径。 */
    public static final float SOUND_VOLUME = 0.5F;
    /** 粒子数/速度：minecraft:enchanted_hit，delta 全 0。 */
    public static final int PARTICLE_COUNT = 5;
    public static final double PARTICLE_SPEED = 0.5D;

    /** 进入判据（调用方保证服务端 + 开关开启）。 */
    public static boolean shouldEnter(boolean overStressed, float panelValue, boolean active,
            float theoreticalSpeed) {
        return overStressed
                && Math.abs(panelValue) > PANEL_THRESHOLD_RPM
                && active
                && theoreticalSpeed != 0.0F;
    }

    /** 退出判据：过载恢复或断电（active=false，FE 耗尽）任一即重置。 */
    public static boolean shouldReset(boolean overStressed, boolean active) {
        return !overStressed || !active;
    }

    /**
     * 客户端派生判据（护目镜显示用）：服务端锁存标志不进同步包，客户端用三项
     * 已同步状态重建等价条件——{@code isOverStressed()}（Network 标签同步）、
     * {@code generatedSpeed.getValue()}（behaviour 同步）、
     * {@code getTheoreticalSpeed()}（Speed 标签同步）。
     */
    public static boolean clientDerived(boolean overStressed, float panelValue, float theoreticalSpeed) {
        return overStressed
                && Math.abs(panelValue) > PANEL_THRESHOLD_RPM
                && theoreticalSpeed != 0.0F;
    }

    /** 锁存期副作用：粒子每 tick；音效在 latchAge%20==0（含进入当 tick）时播。 */
    public static void tickEffects(ServerLevel level, BlockPos pos, int latchAgeTicks) {
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, x, y, z,
                PARTICLE_COUNT, 0.0D, 0.0D, 0.0D, PARTICLE_SPEED);
        if (latchAgeTicks % SOUND_INTERVAL_TICKS == 0) {
            level.playSound(null, x, y, z,
                    ModSounds.ELECTRIC_MOTOR_OVERSTRESS.get(), SoundSource.BLOCKS, SOUND_VOLUME, 1.0F);
        }
    }

    private MotorOverstressLatch() {
    }
}
