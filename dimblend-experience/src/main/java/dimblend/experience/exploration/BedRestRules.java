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

/** Beds provide rest, without advancing time, in the overworld and rotating world. */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class BedRestRules {
    /** 入睡预热：满 100 tick（5 秒）才给生命恢复与爱心粒子。 */
    public static final int REST_WARMUP_TICKS = 100;
    /** 预热完成后给一次的生命恢复 II 时长（30 秒），本段睡眠不重复给。 */
    public static final int REST_REGENERATION_DURATION_TICKS = 600;
    /** 预热完成后爱心粒子的播放间隔。 */
    public static final int HEART_PARTICLE_INTERVAL_TICKS = 20;
    /** 每次播放的爱心个数。 */
    public static final int HEART_PARTICLE_COUNT = 3;

    public static boolean applies(Level level) {
        return level.dimension().equals(Level.OVERWORLD)
                || (Config.isLoaded() && RotatingDimension.is(level));
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
