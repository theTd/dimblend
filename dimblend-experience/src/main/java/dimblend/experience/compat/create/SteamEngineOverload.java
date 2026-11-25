package dimblend.experience.compat.create;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlock;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import dimblend.experience.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * H 板块蒸汽引擎过载两阶段（纯服务端 helper；计时状态在 {@link SteamEngineExhaustAccess}，
 * 由 {@code SteamEngineOverloadMixin} 驱动）。
 *
 * <p>字节码事实（Create 6.0.10 运行 jar 反汇编 / {@code javap -p}）：{@code SteamEngineBlockEntity#tick()}
 * 只在服务端跑下半；{@code getShaft()} 自带弱引用重解析（失效→沿 {@code facing} 方向 2 格找
 * {@code PoweredShaftBlockEntity}）；{@code isValid()} 只校验引擎贴锅炉面是否是流体储罐；
 * {@code SmartBlockEntity}（经 {@code SyncedBlockEntity}）声明 {@code write/read/notifyUpdate}，
 * 而 {@code SteamEngineBlockEntity} 自己不覆写 {@code write/read}——所以落盘只能从
 * {@code SmartBlockEntity} 那一层注入。</p>
 *
 * <p>轴被谁重新耦合：{@code ShaftBlock#pickCorrectShaftType} → {@code PoweredShaftBlock#stillValid}
 * 会在<b>放置</b>时把普通传动杆换成动力轴；排气窗口内由
 * {@code PoweredShaftBlockExhaustMixin} 把 {@code stillValid} 压成 false，32 秒结束再
 * {@link #finishExhaust} 把仍在原位的普通传动杆换回动力轴，恢复正常运转。</p>
 *
 * <p>第一阶段（过载连续计时，0..319 tick）：引擎运转（isValid + 轴非 null）且轴过载时
 * overloadAge++；每 2 tick 在引擎中心播 {@code minecraft:cloud}（count=1、delta=0、
 * speed=0.05），每 20 tick 播 {@code steam_overstress}。任一条件消失即清零。</p>
 *
 * <p>第二阶段（满 320 tick = 16 秒）：断轴 + {@code steam_exhaust} + 32 秒（640 tick）排气粒子。
 * 排气音在 age = 0/160/320/480 各播一次（4 次覆盖 32 秒）。引擎本体保留停转，且这 32 秒里
 * 引擎与它贴着的锅炉储罐格都不可破坏/不可扳手拆（见 {@code SteamEngineExhaustGuard}）。</p>
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
        BlockPos shaftPos = shaftPos(level.getBlockState(enginePos), enginePos);
        if (shaftPos == null || !AllBlocks.POWERED_SHAFT.has(level.getBlockState(shaftPos))) {
            return false;
        }
        return level.destroyBlock(shaftPos, true);
    }

    /**
     * 进入 32 秒排气窗口：断轴、写剩余量、{@code notifyUpdate()}（把剩余量同步给客户端，
     * 客户端也要据此把"放置传动杆"预测成普通杆）。
     */
    public static void beginExhaust(ServerLevel level, BlockPos enginePos, SmartBlockEntity engine) {
        severShaft(level, enginePos);
        SteamEngineExhaustAccess.setRemaining(engine, SteamEngineOverloadMath.EXHAUST_DURATION_TICKS);
        engine.notifyUpdate();
    }

    /**
     * 第二阶段收尾 1 tick：排气音（age = 0/160/320/480）+ 排气粒子。
     *
     * @param exhaustAgeTicks 排气年龄（断轴当 tick 为 0）
     * @return 排气播满 32 秒时 true（调用方清状态），否则 false
     */
    public static boolean tickExhaust(ServerLevel level, BlockPos enginePos, int exhaustAgeTicks) {
        double x = enginePos.getX() + 0.5D;
        double y = enginePos.getY() + 0.5D;
        double z = enginePos.getZ() + 0.5D;
        if (SteamEngineOverloadMath.shouldPlayExhaustSound(exhaustAgeTicks)) {
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

    /**
     * 32 秒结束：如果玩家在这段时间里往轴位放了普通传动杆（{@code stillValid} 被压成 false，
     * 所以它留在了普通杆形态），这里把它换回动力轴，引擎随即恢复输出。
     *
     * @return 是否真的换回了动力轴
     */
    public static boolean finishExhaust(ServerLevel level, BlockPos enginePos) {
        BlockPos shaftPos = shaftPos(level.getBlockState(enginePos), enginePos);
        if (shaftPos == null) {
            return false;
        }
        BlockState shaftState = level.getBlockState(shaftPos);
        if (!AllBlocks.SHAFT.has(shaftState)) {
            return false;
        }
        BlockState equivalent = PoweredShaftBlock.getEquivalent(shaftState);
        if (equivalent == null || !AllBlocks.POWERED_SHAFT.has(equivalent)) {
            return false;
        }
        return level.setBlockAndUpdate(shaftPos, equivalent);
    }

    /** 轴位（引擎 facing 方向 2 格）；方块不是蒸汽引擎时 null。 */
    public static BlockPos shaftPos(BlockState engineState, BlockPos enginePos) {
        if (!AllBlocks.STEAM_ENGINE.has(engineState)) {
            return null;
        }
        return SteamEngineBlock.getShaftPos(engineState, enginePos);
    }

    /** 贴在引擎背面的锅炉储罐格（{@code getFacing(state).getOpposite()}，引擎 isValid 同源）。 */
    public static BlockPos boilerPos(BlockState engineState, BlockPos enginePos) {
        return enginePos.relative(SteamEngineBlock.getFacing(engineState).getOpposite());
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
