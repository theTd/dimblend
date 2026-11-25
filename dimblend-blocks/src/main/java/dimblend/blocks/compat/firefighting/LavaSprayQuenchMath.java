package dimblend.blocks.compat.firefighting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 消防水雾浇岩浆的纯数学部分（无 {@code Level}、无注册表访问，可单测）。
 *
 * <p>两块职责：</p>
 * <ol>
 *   <li><b>表面优先</b>：同一条射线（同一原点 + 同一量化方向）可能连续上报多格岩浆，
 *       我们只保留距离最近的那一格——射线先撞到的那格才是"表面"，否则一次喷射会把
 *       表层背后整条岩浆柱一起固化。</li>
 *   <li><b>节流</b>：每 {@value #BURST_INTERVAL_TICKS} gt 最多处理 {@value #BURST_LIMIT}
 *       格，避免一个喷头对着大岩浆湖造成同 tick 内的方块更新风暴。</li>
 * </ol>
 */
public final class LavaSprayQuenchMath {

    /** 每轮 flush 最多真正冷却的格数。 */
    public static final int BURST_LIMIT = 8;

    /** 队列 flush 间隔（游戏刻）。 */
    public static final int BURST_INTERVAL_TICKS = 5;

    /** 排队上限：防恶意/异常喷头把队列撑爆（超出后新射线直接丢弃）。 */
    public static final int MAX_PENDING_PER_LEVEL = 4096;

    /** 方向量化精度：1/1000。 */
    private static final double DIR_QUANT = 1000.0D;

    /**
     * 一条射线的身份：原点打包坐标 + 量化方向。
     *
     * @param originPacked {@link BlockPos#asLong()} 打包后的原点
     */
    public record RayKey(long originPacked, int dirX, int dirY, int dirZ) {
    }

    /**
     * 计算射线键；方向缺失（空气球扫掠 / 路径扫掠样本）或长度近似为 0 时返回 {@code null}。
     */
    public static RayKey rayKey(BlockPos origin, Vec3 direction) {
        if (origin == null || direction == null) {
            return null;
        }
        double lengthSqr = direction.lengthSqr();
        if (!(lengthSqr > 1.0E-9D) || !Double.isFinite(lengthSqr)) {
            return null;
        }
        Vec3 normalized = direction.scale(1.0D / Math.sqrt(lengthSqr));
        return new RayKey(origin.asLong(), quantize(normalized.x), quantize(normalized.y),
                quantize(normalized.z));
    }

    /** 方向分量量化。 */
    public static int quantize(double component) {
        if (!Double.isFinite(component)) {
            return 0;
        }
        return (int) Math.round(component * DIR_QUANT);
    }

    /** 表面优先：只有严格更近的命中才替换已排队的那一格（等距保留先到的）。 */
    public static boolean isBetterSurfaceHit(double queuedDistance, double candidateDistance) {
        if (!Double.isFinite(candidateDistance)) {
            return false;
        }
        return candidateDistance < queuedDistance;
    }

    /** 本轮真正处理的格数：{@code min(pending, limit)}，负数收敛到 0。 */
    public static int burstSize(int pending, int limit) {
        if (pending <= 0 || limit <= 0) {
            return 0;
        }
        return Math.min(pending, limit);
    }

    /** 是否还应该继续排队（{@code MAX_PENDING_PER_LEVEL} 硬上限）。 */
    public static boolean hasRoom(int pendingCount) {
        return pendingCount < MAX_PENDING_PER_LEVEL;
    }

    /**
     * 喷头水雾是否应当进入冷却队列。
     *
     * <p>严格四条：服务端、方向存在（排除 4 格空气球扫掠/路径扫掠样本）、没被点燃、
     * 流体是水，且命中格是纯岩浆格。</p>
     */
    public static boolean shouldQueue(boolean serverSide, boolean directionPresent, boolean ignited,
            boolean waterFluid, boolean pureLavaCell) {
        return serverSide && directionPresent && !ignited && waterFluid && pureLavaCell;
    }

    private LavaSprayQuenchMath() {
    }
}
