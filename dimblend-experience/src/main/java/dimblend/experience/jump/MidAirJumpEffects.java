package dimblend.experience.jump;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 二段跳脚底 cloud 粒子。位置一律用 {@code getX()/getY()/getZ()} 世界坐标：
 * Sable 只在发移动包的一瞬间把玩家位置换成子层级局部坐标，其余时刻实体坐标就是
 * 世界坐标，直接生成即可，不需要（也不应该）做 pose 换算。
 */
public final class MidAirJumpEffects {

    private static final int RING_PARTICLES = 10;
    private static final double RING_RADIUS = 0.25D;
    private static final double OUTWARD_SPEED = 0.04D;
    private static final double UPWARD_SPEED = 0.01D;

    /** 本地客户端立即反馈：脚底一圈水平扩散的 cloud。 */
    public static void spawnClientCloud(Player player) {
        double centerX = player.getX();
        double centerY = player.getY() + 0.05D;
        double centerZ = player.getZ();
        for (int i = 0; i < RING_PARTICLES; i++) {
            double angle = Math.PI * 2.0D * (double) i / (double) RING_PARTICLES;
            double offsetX = Math.cos(angle) * RING_RADIUS;
            double offsetZ = Math.sin(angle) * RING_RADIUS;
            player.level().addParticle(ParticleTypes.CLOUD,
                    centerX + offsetX, centerY, centerZ + offsetZ,
                    offsetX * OUTWARD_SPEED / RING_RADIUS, UPWARD_SPEED,
                    offsetZ * OUTWARD_SPEED / RING_RADIUS);
        }
    }

    /**
     * 服务端：只发给来源之外 64 格内的观察者，来源玩家本地已生成一份，避免粒子翻倍。
     */
    public static void broadcastServerCloudToOthers(ServerPlayer source) {
        ServerLevel level = source.serverLevel();
        double x = source.getX();
        double y = source.getY() + 0.05D;
        double z = source.getZ();
        for (ServerPlayer other : level.players()) {
            if (other == source || other.distanceToSqr(source) > 64.0D * 64.0D) {
                continue;
            }
            level.sendParticles(other, ParticleTypes.CLOUD, false,
                    x, y, z, RING_PARTICLES, RING_RADIUS, 0.05D, RING_RADIUS, 0.02D);
        }
    }

    private MidAirJumpEffects() {
    }
}
