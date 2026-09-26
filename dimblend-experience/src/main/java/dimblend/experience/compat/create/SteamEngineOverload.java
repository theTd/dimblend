package dimblend.experience.compat.create;

import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;

import dimblend.experience.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

/**
 * H 板块蒸汽引擎过载两阶段（纯服务端、无状态 helper；计时字段在
 * {@code SteamEngineOverloadMixin} 内 @Unique 持有：内存态，区块卸载/重进清零）。
 *
 * <p>字节码事实（Create 6.0.10 运行 jar 反汇编）：{@code SteamEngineBlockEntity.tick()}
 * 只在服务端跑下半（{@code isClientSide} 直接 return）；{@code getShaft()} 自带
 * 弱引用重解析（失效→沿 {@code facing} 方向 2 格找 {@code PoweredShaftBlockEntity}）
 * ——轴被手拆/替换后返回 null；{@code isValid()} 只校验引擎贴锅炉面是否是流体储罐。
 * 过载信号源 = 轴 {@code isOverStressed()}（{@code KineticBlockEntity} 同步字段，
 * 服务端每 tick 维护），与 CDG B5 引信同源口径。</p>
 *
 * <p>第一阶段（过载连续计时，0..319 tick）：引擎运转（isValid + 轴非 null）且轴过载时
 * overloadAge++；每 2 tick 在引擎中心播 {@code minecraft:cloud}（count=1、delta=0、
 * speed=0.05），每 20 tick 播 {@code steam_overstress}（1 秒间隔，素材 1.49 秒近无缝）。
 * 任一条件消失（过载解除/停机/轴缺失）即清零，声音粒子同时停——中期解除无残留。</p>
 *
 * <p>第二阶段（满 320 tick = 16 秒）：断轴 + {@code steam_exhaust} 1 次 +
 * 8 秒（160 tick）排气粒子（每 2 tick {@code minecraft:cloud} count=5、delta=0、
 * speed=0.1）。只破仍是动力轴的格（{@code AllBlocks.POWERED_SHAFT.has}），
 * {@code destroyBlock(shaftPos, true)} 按战利品表掉成传动杆（powered_shaft 的 loot
 * 本来就是 {@code create:shaft}），引擎本体保留停转。过载解除不中断排气；
 * 引擎被拆/区块卸载则 mixin 不再 tick，自然终止（无幽灵粒子）。</p>
 */
public final class SteamEngineOverload {

    /**
     * 第一阶段推进 1 tick（调用方保证：服务端、总开关已开、引擎运转中）。
     *
     * @param overloadAgeTicks 当前过载年龄（进入时为 0）
     * @return 满 16 秒窗口时 true（调用方转第二阶段），否则 false
     */
    public static boolean tickOverload(ServerLevel level, BlockPos enginePos, int overloadAgeTicks) {
        double x = enginePos.getX() + 0.5D;
        double y = enginePos.getY() + 0.5D;
        double z = enginePos.getZ() + 0.5D;
        if (SteamEngineOverloadMath.shouldEmitOverloadParticle(overloadAgeTicks)) {
            level.sendParticles(ParticleTypes.CLOUD, x, y, z,
                    SteamEngineOverloadMath.OVERLOAD_PARTICLE_COUNT, 0.0D, 0.0D, 0.0D,
                    SteamEngineOverloadMath.OVERLOAD_PARTICLE_SPEED);
        }
        if (SteamEngineOverloadMath.shouldPlayOverloadSound(overloadAgeTicks)) {
            level.playSound(null, x, y, z,
                    ModSounds.STEAM_OVERSTRESS.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return SteamEngineOverloadMath.windowElapsed(overloadAgeTicks);
    }

    /**
     * 第二阶段断轴（满 16 秒当 tick，调用方保证服务端 + 总开关已开）。
     * 只破仍是 Create 动力轴的格；其他方块（被换/已空）不动。
     *
     * @return 轴位置仍是动力轴且破坏成功时 true
     */
    public static boolean severShaft(ServerLevel level, BlockPos enginePos) {
        BlockPos shaftPos = SteamEngineBlock.getShaftPos(level.getBlockState(enginePos), enginePos);
        if (!com.simibubi.create.AllBlocks.POWERED_SHAFT.has(level.getBlockState(shaftPos))) {
            return false;
        }
        return level.destroyBlock(shaftPos, true);
    }

    /**
     * 第二阶段收尾 1 tick：排气音（仅 exhaustAge==0 的断轴当 tick 一次）+ 排气粒子。
     *
     * @param exhaustAgeTicks 排气年龄（断轴当 tick 为 0）
     * @return 排气播满 8 秒时 true（调用方清状态），否则 false
     */
    public static boolean tickExhaust(ServerLevel level, BlockPos enginePos, int exhaustAgeTicks) {
        double x = enginePos.getX() + 0.5D;
        double y = enginePos.getY() + 0.5D;
        double z = enginePos.getZ() + 0.5D;
        if (exhaustAgeTicks == 0) {
            level.playSound(null, x, y, z,
                    ModSounds.STEAM_EXHAUST.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        if (SteamEngineOverloadMath.shouldEmitExhaustParticle(exhaustAgeTicks)) {
            level.sendParticles(ParticleTypes.CLOUD, x, y, z,
                    SteamEngineOverloadMath.EXHAUST_PARTICLE_COUNT, 0.0D, 0.0D, 0.0D,
                    SteamEngineOverloadMath.EXHAUST_PARTICLE_SPEED);
        }
        return SteamEngineOverloadMath.exhaustDone(exhaustAgeTicks);
    }

    /** 运转判据：引擎贴锅炉（isValid）+ 轴在位（getShaft 非 null）。原版 tick 服务端下半同源。 */
    public static boolean isRunning(
            com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity engine) {
        if (!engine.isValid()) {
            return false;
        }
        return engine.getShaft() != null;
    }

    /** 过载判据：轴 {@code isOverStressed()}（CDG B5 同源）。 */
    public static boolean isOverloaded(PoweredShaftBlockEntity shaft) {
        return shaft.isOverStressed();
    }

    private SteamEngineOverload() {
    }
}
