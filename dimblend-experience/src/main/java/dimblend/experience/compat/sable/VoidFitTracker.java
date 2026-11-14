package dimblend.experience.compat.sable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * 结构空位拟合的真相源：每个受跟踪载具当前"应当"有结构空位的世界格集合（target），
 * 与实际已写入世界的格集合（materialized），以及二者按区块的倒排索引、按 (x,z)
 * 柱的柱顶索引（G6 熔毁的熔岩保留口径；封口格也在 target 内，随 target 同步重建）。
 *
 * <p>两个集合分工：</p>
 * <ul>
 * <li>{@code targetCells}：纯几何目标格（体素化 + 封口格 + 熔穿孔塞），驱动区块加载补放
 *     （加载事件只登记、tick 补放队列消化，见 {@code SableVoidFit} 类头的事故口径）与多车厢
 *     共享格过继——绝不混入离开投影但尚未拆完的历史尾迹格；</li>
 * <li>{@code materializedCells}：确认已由我们写入 structure_void 的格，驱动差分与拆除；
 *     同时维护独立的区块倒排 {@code materializedCellsByChunk}，使 {@code VoidFitStrip}
 *     在预算截断续拆或载具消失续拆窗口期仍能把尚未拆完的尾迹空位剥成空气再落盘。</li>
 * </ul>
 *
 * <p>本类刻意不引用任何 Sable 类型：mixin（无 Sable 门控）与单测/GameTest 都可安全触达。
 * 所有访问假定发生在服务端主线程（{@code ChunkMap#save} 的两个调用点均已核实为主线程）；
 * 若未来引入异步保存类优化（如 C2ME）需改并发结构。</p>
 */
public final class VoidFitTracker {

    /** 单个载具的拟合状态。位姿为上次拟合时的拷贝，用于阈值比较。 */
    public static final class VehicleFit {
        public final Vector3d lastPosition = new Vector3d();
        public final Quaterniond lastOrientation = new Quaterniond();
        /** 目标世界格全集（BlockPos.asLong）。 */
        public final LongOpenHashSet targetCells = new LongOpenHashSet();
        /** 各 (x,z) 柱的最高目标格 y（G6 熔毁开启时仅安全柱顶格保留熔岩；随 target 同步重建）。 */
        public final Long2IntOpenHashMap columnTops = new Long2IntOpenHashMap();
        /** 确认已写入 structure_void 的世界格。 */
        public final LongOpenHashSet materializedCells = new LongOpenHashSet();
        /** 慢速自愈倒计时（按扫描周期计，归零强制 refit）。 */
        public int verifyCountdown;
        /** 连续空 target 重试次数（plot 懒加载退避保险丝计数，非空时清零）。 */
        public int emptyRetries;

        public VehicleFit() {
            // 缺柱永不命中（fastutil 缺省 0 会误中 y=0 的合法格；clear/putAll 不动缺省值）
            columnTops.defaultReturnValue(Integer.MIN_VALUE);
        }
    }

    private static final Map<ResourceKey<Level>, VoidFitTracker> BY_DIMENSION = new HashMap<>();

    private final Map<UUID, VehicleFit> vehicles = new HashMap<>();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> targetCellsByChunk = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> materializedCellsByChunk = new Long2ObjectOpenHashMap<>();

    public static VoidFitTracker of(ResourceKey<Level> dimension) {
        return BY_DIMENSION.computeIfAbsent(dimension, key -> new VoidFitTracker());
    }

    @Nullable
    public static VoidFitTracker peek(ResourceKey<Level> dimension) {
        return BY_DIMENSION.get(dimension);
    }

    /** 全局早退：任何维度既无目标格也无未拆完的已物化格时，保存剥离 mixin 零开销返回。 */
    public static boolean hasAny() {
        for (VoidFitTracker tracker : BY_DIMENSION.values()) {
            if (!tracker.targetCellsByChunk.isEmpty() || !tracker.materializedCellsByChunk.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public Map<UUID, VehicleFit> vehicles() {
        return vehicles;
    }

    /** 指定区块内的目标格集合；无则 {@code null}。 */
    @Nullable
    public LongSet targetCellsOfChunk(ChunkPos pos) {
        return targetCellsByChunk.get(pos.toLong());
    }

    /** 指定区块内已物化（写入世界但尚未拆除）的格集合；无则 {@code null}。 */
    @Nullable
    public LongSet materializedCellsOfChunk(ChunkPos pos) {
        return materializedCellsByChunk.get(pos.toLong());
    }

    /** 指定世界格是否属于当前拟合目标或尚未拆完的已物化尾迹空位（零分配查询）。 */
    public boolean isFittedCell(long cell) {
        long key = chunkKey(cell);
        LongOpenHashSet target = targetCellsByChunk.get(key);
        if (target != null && target.contains(cell)) {
            return true;
        }
        LongOpenHashSet materialized = materializedCellsByChunk.get(key);
        return materialized != null && materialized.contains(cell);
    }

    /** 记录某载具已将指定世界格写入为 structure_void，并同步加入区块倒排。 */
    public void addMaterialized(UUID id, long cell) {
        VehicleFit fit = vehicles.get(id);
        if (fit == null || !fit.materializedCells.add(cell)) {
            return;
        }
        materializedCellsByChunk.computeIfAbsent(chunkKey(cell), key -> new LongOpenHashSet()).add(cell);
    }

    /** 从指定载具的已物化集合摘除一格，并在无其它载具仍持有该格时同步摘除区块倒排。 */
    public void removeMaterialized(UUID id, long cell) {
        VehicleFit fit = vehicles.get(id);
        if (fit == null || !fit.materializedCells.remove(cell)) {
            return;
        }
        unindexMaterializedIfUnowned(id, cell);
    }

    /**
     * 供通过 {@code fit.materializedCells.iterator()} 调用 {@code iterator.remove()} 的路径同步维护倒排：
     * 仅当无其它载具仍将该格记在 materialized 账上时，从 {@code materializedCellsByChunk} 摘除。
     */
    public void unindexMaterializedIfUnowned(UUID self, long cell) {
        if (materializedByOtherVehicle(self, cell)) {
            return;
        }
        removeFromChunkIndex(materializedCellsByChunk, cell);
    }

    /**
     * 增量替换某载具的目标格集合并维护 chunk 倒排（不动 materialized）。
     */
    public void setTargetCells(UUID id, LongOpenHashSet newTarget) {
        setTargetCells(id, newTarget, null);
    }

    /**
     * 增量替换某载具的目标格集合并维护 chunk 倒排（不动 materialized）。
     * 倒排按格计引用：共享格只有在没有任何其他载具 target 时才从倒排摘除；
     * 原本已在目标集内的格直接保留，不再全表重哈希插入。
     *
     * @param precomputedTops 调用方已基于最终 {@code newTarget} 算好的柱顶表；为 {@code null} 时按需现算
     */
    public void setTargetCells(UUID id, LongOpenHashSet newTarget, @Nullable Long2IntOpenHashMap precomputedTops) {
        VehicleFit fit = vehicles.get(id);
        if (fit == null) {
            return;
        }
        boolean anyRemoved = false;
        for (long cell : fit.targetCells) {
            if (newTarget.contains(cell)) {
                continue; // 仍在目标里：倒排保持
            }
            anyRemoved = true;
            if (targetedByOtherVehicle(id, cell)) {
                continue; // 其他载具仍 target 此格：倒排保持
            }
            removeFromChunkIndex(targetCellsByChunk, cell);
        }
        boolean anyAdded = false;
        for (long cell : newTarget) {
            if (fit.targetCells.contains(cell)) {
                continue; // 原已在目标里：倒排已有，跳过重复插入
            }
            anyAdded = true;
            targetCellsByChunk.computeIfAbsent(chunkKey(cell), key -> new LongOpenHashSet()).add(cell);
        }
        if (!anyRemoved && !anyAdded) {
            return;
        }
        fit.targetCells.clear();
        fit.targetCells.addAll(newTarget);
        fit.columnTops.clear();
        fit.columnTops.putAll(precomputedTops != null ? precomputedTops : VoidFitColumnTops.of(newTarget));
    }

    private boolean targetedByOtherVehicle(UUID self, long cell) {
        for (Map.Entry<UUID, VehicleFit> entry : vehicles.entrySet()) {
            if (!entry.getKey().equals(self) && entry.getValue().targetCells.contains(cell)) {
                return true;
            }
        }
        return false;
    }

    private boolean materializedByOtherVehicle(UUID self, long cell) {
        for (Map.Entry<UUID, VehicleFit> entry : vehicles.entrySet()) {
            if (!entry.getKey().equals(self) && entry.getValue().materializedCells.contains(cell)) {
                return true;
            }
        }
        return false;
    }

    /** 载具消失：摘除条目与两张区块倒排（世界方块拆除由调用方在调用前按 materialized 完成）。 */
    public void removeVehicle(UUID id) {
        VehicleFit fit = vehicles.get(id);
        if (fit == null) {
            return;
        }
        if (!fit.targetCells.isEmpty()) {
            setTargetCells(id, new LongOpenHashSet());
        }
        if (!fit.materializedCells.isEmpty()) {
            LongOpenHashSet remaining = new LongOpenHashSet(fit.materializedCells);
            fit.materializedCells.clear();
            for (long cell : remaining) {
                unindexMaterializedIfUnowned(id, cell);
            }
        }
        vehicles.remove(id);
    }

    private static void removeFromChunkIndex(Long2ObjectOpenHashMap<LongOpenHashSet> index, long cell) {
        long key = chunkKey(cell);
        LongOpenHashSet chunkCells = index.get(key);
        if (chunkCells != null) {
            chunkCells.remove(cell);
            if (chunkCells.isEmpty()) {
                index.remove(key);
            }
        }
    }

    private static long chunkKey(long blockPosLong) {
        return ChunkPos.asLong(BlockPos.getX(blockPosLong) >> 4, BlockPos.getZ(blockPosLong) >> 4);
    }

    private VoidFitTracker() {
    }
}
