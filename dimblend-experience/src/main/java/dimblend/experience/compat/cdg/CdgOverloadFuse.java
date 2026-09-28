package dimblend.experience.compat.cdg;

import dimblend.experience.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.function.BooleanSupplier;

/**
 * B6 过载引信（不可中断，三类柴油机共用，确认窗口见 {@link CdgOverloadMath}）：
 * 运转中过载连续 2 秒确认后 {@link #startFuse} 点引信——存档/区块加载期
 * kinetic 网络重建的短暂误报在此窗口内被滤掉，与蒸汽机 H 板块 16 秒持续确认同设计语言；
 * 确认后每服务端 tick {@link #tickFuse} 递减，红石关停/燃尽/负载恢复都不取消；
 * 倒计时期间每 tick 在引擎中心播 {@code minecraft:large_smoke}
 * （delta 0.2,0.2,0.2 / speed 0 / count 10），持续整个 6 秒；
 * 120 tick（6 秒）到时播放 {@code entity.generic.explode} 一次 + 爆炸粒子
 * （{@code minecraft:explosion}，delta 1,1,1 / speed 0 / count 100），再破坏自毁掉落。
 * 仅自毁掉落，无地形/实体伤害（不调 {@code level.explode}）。
 */
public final class CdgOverloadFuse {

    /** 引信时长：6 秒 × 20 tps。 */
    public static final int FUSE_TICKS = 120;
    /** 自爆粒子数：与 /particle minecraft:explosion ~ ~ ~ 1 1 1 0 100 同口径。 */
    public static final int DETONATE_PARTICLE_COUNT = 100;
    /** 引信期烟雾粒子数（每 tick）：与 /particle minecraft:large_smoke ~ ~ ~ 0.2 0.2 0.2 0 10 同口径。 */
    public static final int SMOKE_PARTICLE_COUNT = 10;
    /** 引信期烟雾三轴散布 delta。 */
    private static final double SMOKE_DELTA = 0.2;

    private CdgOverloadFuse() {
    }

    /** 点引信：警告音一次 + 出力闩锁归零（燃油门控走闩锁口径），倒计时 120 tick；确认计数清零。 */
    public static void startFuse(ServerLevel level, BlockPos pos, CdgEngineState state) {
        state.fuseActive = true;
        state.overloadLatched = true;
        state.fuseTicksLeft = FUSE_TICKS;
        state.overloadTicks = 0;
        state.rampTicks = 0;
        state.fluctTicksLeft = 0;
        level.playSound(null, pos, ModSounds.DIESEL_OVERSTRESS.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    /**
     * 推进引信 1 tick：先播一次引信烟雾；到时播放爆炸声 + 粒子并调 destroy 自毁。
     *
     * @param destroy 调用方提供的自毁动作（含巨型机的摘轴 + BE 存活守卫），返回是否破坏成功
     * @return 自毁失败回退（引信清除、闩锁保留）时为 true，调用方需 setChanged；其余 false
     */
    public static boolean tickFuse(ServerLevel level, BlockPos pos, CdgEngineState state, BooleanSupplier destroy) {
        // 引信期烟雾：倒计时内每 tick 一次（含到时 tick，与爆炸粒子同帧无冲突）
        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SMOKE_PARTICLE_COUNT, SMOKE_DELTA, SMOKE_DELTA, SMOKE_DELTA, 0.0);
        if (--state.fuseTicksLeft > 0) {
            return false;
        }
        level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.EXPLOSION,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                DETONATE_PARTICLE_COUNT, 1.0, 1.0, 1.0, 0.0);
        state.fuseActive = false;
        state.fuseTicksLeft = 0;
        if (destroy.getAsBoolean()) {
            return false;
        }
        // 破坏失败（极端情况）回退闩锁：停机等重新加油，不再重试爆炸
        return true;
    }
}
