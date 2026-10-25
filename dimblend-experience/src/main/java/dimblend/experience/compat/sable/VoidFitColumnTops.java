package dimblend.experience.compat.sable;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

/**
 * G6 熔岩柱顶/封口索引（纯计算，无可变状态）：按 (x,z) 柱取最高目标格 y，并给出
 * "安全柱顶"（正下方同柱下一格也在目标集内）与"封口格"（下方悬空柱顶的正下方一格）。
 *
 * <p>体素化只收非空气方块（{@code VoidFitVoxelizer#forEachProjectedBlock} 跳过 air），
 * 载具舱内空气不是目标格。因此"柱顶保留熔岩"只有在正下方还有目标格时才安全：
 * 下方目标格（实体投影，或本类补的封口格）最终被拟合成 structure_void，原版
 * {@code FlowingFluid#canHoldFluid}（FlowingFluid.java:394-412）在 :408 显式排除
 * structure_void，熔岩不能下行。下方的目标格一旦离开 target（方块被熔掉、投影
 * 收缩），柱顶在同一轮 refit 重新判定——保留格不入 materialized、每轮重试——随即
 * 被驱逐，不会把熔岩带进新投影。</p>
 *
 * <p>单格柱/悬空柱顶（正下方是舱内空气、悬空间隙或外挑甲板下沿）若不补封口，
 * 保留的熔岩会直接从完整船壳里漏下去——这正是 2026-10-26 修的缺口。{@link
 * #sealCellsFor} 给每个下方悬空的柱顶补一格目标格（正下方），与保守覆盖同性质：
 * 不可见、无碰撞、挡流体，随 target 一起物化、拆除、剥盘。封口格本身不是柱顶
 * （它在柱顶下方），故不改变 {@link #isTop} 的柱顶语义。</p>
 *
 * <p>安全性的最后一层在 {@code VoidFitApplier#placeVoid}：真正保留熔岩时还要求
 * 正下方世界格此刻是 structure_void 或 blocksMotion() 方块——封口格可能因区块
 * 未加载、写入预算耗尽或压中非流体方块而尚未物化，几何条件本身有窗口；世界侧
 * 检查把窗口关掉（宁可驱逐熔岩，安全方向）。</p>
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

    /** 同 (x,z) 柱正下方一格（y-1）。 */
    public static long cellBelow(long cell) {
        return BlockPos.asLong(BlockPos.getX(cell), BlockPos.getY(cell) - 1, BlockPos.getZ(cell));
    }

    /**
     * 安全柱顶：是柱顶，且正下方同柱下一格仍在目标集内。只有这种柱顶保留熔岩时，
     * 熔岩下方才一定有（或即将有）structure_void 顶着；单格柱、间隙、外挑下沿都会
     * 返回 false，调用方不得保留（要么补封口格后再判，要么驱逐）。
     */
    public static boolean isSealedTop(Long2IntMap tops, LongSet cells, long cell) {
        return isTop(tops, cell) && cells.contains(cellBelow(cell));
    }

    /**
     * 给每个"正下方不在目标集内"的柱顶补一格封口目标格（正下方）。返回新增格集合，
     * 不修改入参；{@code minY} 以下（世界底）不补（下方在世界外，保留熔岩无意义，
     * 调用方按未密封处理即驱逐）。
     */
    public static LongOpenHashSet sealCellsFor(LongSet cells, int minY) {
        Long2IntOpenHashMap tops = of(cells);
        LongOpenHashSet seals = new LongOpenHashSet();
        for (long cell : cells) {
            if (!isTop(tops, cell)) {
                continue;
            }
            long below = cellBelow(cell);
            if (!cells.contains(below) && BlockPos.getY(below) >= minY) {
                seals.add(below);
            }
        }
        return seals;
    }

    private VoidFitColumnTops() {
    }
}
