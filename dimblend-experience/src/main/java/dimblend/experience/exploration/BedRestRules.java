package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;

/**
 * Beds provide rest, without advancing time, in the overworld and rotating world.
 * rotating exception (2026-10-23): night/thunder sleep skips to morning vanilla-style again
 * (daytime naps stay rest-only); the write lands on the shared overworld clock through
 * RotatingLevelData. See {@link BedRestRules#suppressesTimeSkip(Level)}.
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class BedRestRules {
    /** 生命恢复 X 的 amplifier（9 即界面显示等级 10）。 */
    public static final int REST_REGENERATION_AMPLIFIER = 9;
    /** 生命恢复 X 的时长：160 tick（8 秒），与自动起身对齐，本段睡眠只给一次。 */
    public static final int REST_REGENERATION_DURATION_TICKS = 160;
    /** 爱心粒子的播放间隔：躺下第 1 tick 起每 20 tick（1 秒）一次。 */
    public static final int HEART_PARTICLE_INTERVAL_TICKS = 20;
    /** 每次播放的爱心个数。 */
    public static final int HEART_PARTICLE_COUNT = 3;
    /** 满 160 tick（8 秒）由服务端让玩家自动起身。 */
    public static final int REST_AUTO_WAKE_TICKS = 160;

    /** 纯规则：本段睡眠第 1 tick 给一次回血，之后不再给。 */
    public static boolean grantsRegenerationOnTick(int restTicks) {
        return restTicks == 1;
    }

    /** 纯规则：躺下第 1 tick 起，每 {@link #HEART_PARTICLE_INTERVAL_TICKS} tick 播一次爱心。 */
    public static boolean emitsHeartsOnTick(int restTicks) {
        return restTicks > 0 && (restTicks - 1) % HEART_PARTICLE_INTERVAL_TICKS == 0;
    }

    /** 纯规则：满 {@link #REST_AUTO_WAKE_TICKS} tick 自动起身。 */
    public static boolean autoWakesOnTick(int restTicks) {
        return restTicks >= REST_AUTO_WAKE_TICKS;
    }

    public static boolean applies(Level level) {
        return level.dimension().equals(Level.OVERWORLD)
                || (Config.isLoaded() && RotatingDimension.is(level));
    }

    /**
     * 跳夜抑制口径（2026-10-23 起 rotating 恢复夜晚跳夜）：主世界永不跳夜（纯休息式睡眠）；
     * rotating 白天非雷雨时抑制（白天小睡只休息，不带着跳一整天），夜晚/雷雨按原版语义
     * 跳到早晨、强制起床、清天气——写时间经 RotatingLevelData 穿透到主世界共享时钟。
     * 其余维度不动原版行为。
     */
    public static boolean suppressesTimeSkip(Level level) {
        boolean overworld = level.dimension().equals(Level.OVERWORLD);
        boolean rotating = Config.isLoaded() && RotatingDimension.is(level);
        return suppressesTimeSkip(overworld, rotating, level.isDay(), level.isThundering());
    }

    /** 纯判定核心，与 Level 解耦便于单测。 */
    static boolean suppressesTimeSkip(boolean overworld, boolean rotating, boolean isDay, boolean thundering) {
        if (overworld) return true;
        if (rotating) return isDay && !thundering;
        return false;
    }

    public static boolean isResting(Player player) {
        return !player.level().isClientSide && player.isAlive() && player.isSleeping()
                && applies(player.level());
    }

    /**
     * 在床尾广播爱心粒子（第一人称躺在床头，头部位置的粒子贴脸看不到）。
     * 床 FACING 由床尾指向床头，床尾中心 = 床头中心反向一格；中心经 Sable 世界
     * 位置投影，载具上的床（plot 本地坐标/随载具旋转）也落到真实世界位置。
     * 床已不存在（被拆/睡觉位丢失）时退回玩家身体上方。
     */
    public static void showRestHearts(Player player) {
        if (!(player.level() instanceof ServerLevel level) || !isResting(player)) return;
        Vec3 center = player.getSleepingPos()
                .map(pos -> bedFootCenter(level, pos))
                .orElse(null);
        if (center == null) {
            center = SableWorldPosition.project(level, player.position());
        }
        center = center.add(0.0, 0.6, 0.0);
        level.sendParticles(ParticleTypes.HEART, center.x, center.y, center.z,
                HEART_PARTICLE_COUNT, 0.35, 0.15, 0.35, 0.0);
    }

    /** 床尾方块中心的世界坐标；睡觉位已不是床时返回 null。 */
    private static Vec3 bedFootCenter(ServerLevel level, BlockPos headPos) {
        if (!(level.getBlockState(headPos).getBlock() instanceof BedBlock)) return null;
        var facing = level.getBlockState(headPos).getValue(BedBlock.FACING);
        Vec3 footCenter = Vec3.atCenterOf(headPos).add(-facing.getStepX(), 0.0, -facing.getStepZ());
        return SableWorldPosition.project(level, footCenter);
    }

    @SubscribeEvent
    public static void onSleepAttempt(CanPlayerSleepEvent event) {
        if (!applies(event.getLevel())) return;
        var problem = event.getProblem();
        if (problem == Player.BedSleepingProblem.NOT_POSSIBLE_NOW
                || problem == Player.BedSleepingProblem.NOT_SAFE) {
            event.setProblem(null);
        }
    }

    @SubscribeEvent
    public static void onContinueSleeping(CanContinueSleepingEvent event) {
        if (event.getEntity() instanceof Player player && applies(player.level())
                && event.getProblem() == Player.BedSleepingProblem.NOT_POSSIBLE_NOW) {
            event.setContinueSleeping(true);
        }
    }

    private BedRestRules() { }
}
