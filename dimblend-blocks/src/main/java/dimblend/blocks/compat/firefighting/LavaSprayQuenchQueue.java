package dimblend.blocks.compat.firefighting;

import java.util.LinkedHashMap;
import java.util.Map;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.firefighting.LavaSprayQuenchMath.RayKey;
import dimblend.blocks.fragile.WaterBucketLavaQuench;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 消防水雾命中岩浆后的延迟冷却队列（每 {@value LavaSprayQuenchMath#BURST_INTERVAL_TICKS} gt
 * 最多处理 {@value LavaSprayQuenchMath#BURST_LIMIT} 格）。
 *
 * <p>为什么要排队而不是命中即冷却：一次喷射会在极短时间里对同一条射线上多格岩浆、
 * 甚至一整片湖面反复触发回调。立即冷却会让喷头变成"一键清湖"，且同 tick 大量
 * {@code setBlockAndUpdate} 造成区块更新风暴。这里先按射线去重（表面优先），再按
 * 节流预算摊到后续 tick。</p>
 *
 * <p>服务器 tick 路径只通过 {@code getChunkSource().getChunkNow()} 触碰区块
 * （{@link WaterBucketLavaQuench#isPureLavaCell(ServerLevel, BlockPos)} 内部已如此实现），
 * 绝不在 tick 里同步加载区块。</p>
 */
public final class LavaSprayQuenchQueue {

    private static final Map<ResourceKey<Level>, LinkedHashMap<RayKey, Pending>> QUEUES =
            new LinkedHashMap<>();

    /** 一条已排队的冷却请求。 */
    private record Pending(BlockPos pos, double distance) {
    }

    /**
     * 吸入一次喷头命中：同一射线只留最近的一格。
     *
     * @param origin      喷头射线的世界原点（消防模组 {@code context.origin()}）
     * @param hitLocation 命中点世界坐标（表面优先按它算距离）
     * @param direction   射线方向；{@code null} 表示空气球/路径扫掠样本，直接忽略
     * @param lavaPos     命中到的岩浆格
     */
    static void offer(ServerLevel level, Vec3 origin, Vec3 hitLocation, Vec3 direction, BlockPos lavaPos) {
        if (origin == null) {
            return;
        }
        RayKey key = LavaSprayQuenchMath.rayKey(BlockPos.containing(origin), direction);
        if (key == null || lavaPos == null) {
            return;
        }
        LinkedHashMap<RayKey, Pending> queue =
                QUEUES.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>());
        Pending existing = queue.get(key);
        double distance = hitLocation == null ? 0.0D : origin.distanceTo(hitLocation);
        if (existing != null) {
            // 表面优先：只有严格更近才替换
            if (LavaSprayQuenchMath.isBetterSurfaceHit(existing.distance(), distance)) {
                queue.put(key, new Pending(lavaPos.immutable(), distance));
            }
            return;
        }
        if (!LavaSprayQuenchMath.hasRoom(queue.size())) {
            return;
        }
        queue.put(key, new Pending(lavaPos.immutable(), distance));
    }

    /**
     * 节流处理本 tick 的预算；已不再是纯岩浆的格子直接丢弃，不占预算。
     */
    static void flush(ServerLevel level) {
        LinkedHashMap<RayKey, Pending> queue = QUEUES.get(level.dimension());
        if (queue == null || queue.isEmpty()) {
            QUEUES.remove(level.dimension());
            return;
        }
        int budget = LavaSprayQuenchMath.burstSize(queue.size(), LavaSprayQuenchMath.BURST_LIMIT);
        int done = 0;
        var iterator = queue.values().iterator();
        while (iterator.hasNext() && done < budget) {
            Pending pending = iterator.next();
            iterator.remove();
            BlockPos pos = pending.pos();
            try {
                if (!WaterBucketLavaQuench.isPureLavaCell(level, pos)) {
                    // 已被其它效果（含同轮 3x3x3 固化）改掉：静默丢弃，不占预算
                    continue;
                }
                WaterBucketLavaQuench.quenchAsBlueIce(level, pos);
                done++;
            } catch (RuntimeException exception) {
                DimBlendBlocks.LOGGER.warn("Lava spray quench failed at {}", pos, exception);
            }
        }
        if (queue.isEmpty()) {
            QUEUES.remove(level.dimension());
        }
    }

    /** 服务器卸载/退出时清干净，防止跨存档串味。 */
    static void clear(ResourceKey<Level> dimension) {
        QUEUES.remove(dimension);
    }

    private LavaSprayQuenchQueue() {
    }
}
