package dimblend.experience.compat.sable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 * 与实际已写入世界的格集合（materialized），以及 target 按区块的倒排索引。
 *
 * <p>两个集合分工：</p>
 * <ul>
 * <li>{@code targetCells}：体素化的全部目标格，驱动区块加载补放（{@code ChunkEvent.Load}）
 *     与保存剥离（{@code ChunkMapVoidFitStripMixin}）——未加载/被占的格也在其中；</li>
 * <li>{@code materializedCells}：确认已由我们写入 structure_void 的格，驱动差分。
 *     两者分离后，写入预算耗尽时 tracker 可先记 target，下周期按 materialized diff 续跑，
 *     不会丢失未写入部分。</li>
 * </ul>
 *
 * <p>本类刻意不引用任何 Sable 类型：mixin（无 Sable 门控）与 GameTest 都可安全触达。
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
        /** 确认已写入 structure_void 的世界格。 */
        public final LongOpenHashSet materializedCells = new LongOpenHashSet();
        /** 慢速自愈倒计时（按扫描周期计，归零强制 refit）。 */
        public int verifyCountdown;
    }

    private static final Map<ResourceKey<Level>, VoidFitTracker> BY_DIMENSION = new HashMap<>();

    private final Map<UUID, VehicleFit> vehicles = new HashMap<>();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> targetCellsByChunk = new Long2ObjectOpenHashMap<>();

    public static VoidFitTracker of(ResourceKey<Level> dimension) {
        return BY_DIMENSION.computeIfAbsent(dimension, key -> new VoidFitTracker());
    }

    @Nullable
    public static VoidFitTracker peek(ResourceKey<Level> dimension) {
        return BY_DIMENSION.get(dimension);
    }

    /** 全局早退：任何维度都没有目标格时 mixin 零开销返回。 */
    public static boolean hasAny() {
        for (VoidFitTracker tracker : BY_DIMENSION.values()) {
            if (!tracker.targetCellsByChunk.isEmpty()) {
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

    /**
     * 整体替换某载具的目标格集合并重建 chunk 倒排（不动 materialized）。
     * 倒排按格计引用：共享格只有在没有任何其他载具 target 时才从倒排摘除，
     * 否则保存剥离会漏掉仍被别车覆盖的空位。
     */
    public void setTargetCells(UUID id, LongOpenHashSet newTarget) {
        VehicleFit fit = vehicles.get(id);
        if (fit == null) {
            return;
        }
        for (long cell : fit.targetCells) {
            if (newTarget.contains(cell)) {
                continue; // 仍在目标里：倒排保持
            }
            if (targetedByOtherVehicle(id, cell)) {
                continue; // 其他载具仍 target 此格：倒排保持
            }
            long key = chunkKey(cell);
            LongOpenHashSet chunkCells = targetCellsByChunk.get(key);
            if (chunkCells != null) {
                chunkCells.remove(cell);
                if (chunkCells.isEmpty()) {
                    targetCellsByChunk.remove(key);
                }
            }
        }
        fit.targetCells.clear();
        fit.targetCells.addAll(newTarget);
        for (long cell : newTarget) {
            targetCellsByChunk.computeIfAbsent(chunkKey(cell), key -> new LongOpenHashSet()).add(cell);
        }
    }

    private boolean targetedByOtherVehicle(UUID self, long cell) {
        for (Map.Entry<UUID, VehicleFit> entry : vehicles.entrySet()) {
            if (!entry.getKey().equals(self) && entry.getValue().targetCells.contains(cell)) {
                return true;
            }
        }
        return false;
    }

    /** 载具消失：摘除条目与倒排（方块拆除由调用方在调用前按 materialized 完成）。 */
    public void removeVehicle(UUID id) {
        setTargetCells(id, new LongOpenHashSet());
        vehicles.remove(id);
    }

    private static long chunkKey(long blockPosLong) {
        return ChunkPos.asLong(BlockPos.getX(blockPosLong) >> 4, BlockPos.getZ(blockPosLong) >> 4);
    }

    private VoidFitTracker() {
    }
}
