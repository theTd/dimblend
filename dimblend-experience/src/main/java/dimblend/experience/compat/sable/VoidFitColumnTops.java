package dimblend.experience.compat.sable;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

/**
 * 结构空位拟合目标格的"列顶"索引（纯计算，无可变状态）：按 (x,z) 柱取最高目标格 y。
 *
 * <p>唯一消费者是 G6 熔毁开启时的熔岩保留口径（{@code VoidFitApplier#placeVoid}）：
 * 从上方流下的岩浆只保留柱顶那一格（流经在车顶线截断、从车顶开始熔毁），柱内更低格
 * 照常驱逐成结构空位——遮挡恢复且不导流。熔毁关闭时本索引算出也无人读。</p>
 */
public final class VoidFitColumnTops {

    /** 柱键打包：高 32 位 x、低 32 位 z（与 {@code ChunkPos.asLong} 同型，仅作哈希键）。 */
    public static long columnKey(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    /** 从目标格全集建柱顶索引。返回表的缺省值为 {@link Integer#MIN_VALUE}，缺柱永不命中。 */
    public static Long2IntOpenHashMap of(LongSet cells) {
        Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
        tops.defaultReturnValue(Integer.MIN_VALUE);
        for (long cell : cells) {
            long column = columnKey(BlockPos.getX(cell), BlockPos.getZ(cell));
            int y = BlockPos.getY(cell);
            if (y > tops.get(column)) {
                tops.put(column, y);
            }
        }
        return tops;
    }

    /**
     * 该格是否其所在柱的最高目标格。前置条件：{@code cell} 属于构建 {@code tops} 的
     * 集合（调用方均为遍历目标格的场景，天然满足）。
     */
    public static boolean isTop(Long2IntMap tops, long cell) {
        return tops.get(columnKey(BlockPos.getX(cell), BlockPos.getZ(cell))) == BlockPos.getY(cell);
    }

    private VoidFitColumnTops() {
    }
}
