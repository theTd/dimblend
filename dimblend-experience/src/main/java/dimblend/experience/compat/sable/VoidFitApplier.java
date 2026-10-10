package dimblend.experience.compat.sable;

import java.util.Map;
import java.util.UUID;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把体素化结果差分应用到世界：新增格放结构空位、移出格拆结构空位。
 *
 * <p>写入纪律（不漏水也不毁图）：</p>
 * <ul>
 * <li>放置只进空气或纯流体格；顶掉流体时带 {@code UPDATE_CLIENTS}（客户端停止渲染水），
 *     空气格不通知客户端（不可见方块无需发包）；都不带邻接更新——放 structure_void 不需要
 *     触发周围方块响应。</li>
 * <li>拆除仅当当前格仍是 structure_void（可能被玩家 replaceable 顶掉、被爆炸 DESTROY）；
 *     拆除带邻接更新，让旁侧自然水回流（回流写入走既有 G3 有限水链路）。</li>
 * <li>区块未加载的格跳过写入：target 集合保留该格，{@code ChunkEvent.Load} 时按表补放；
 *     未加载区块里的空位若随卸载保存，由 {@code ChunkMapVoidFitStripMixin} 兜底不入盘。</li>
 * </ul>
 *
 * <p>预算：单次 {@link #refit} 最多写入 {@link #CELL_BUDGET} 格。差分基于
 * materialized（确认已写入）集合而非 target：预算耗尽时 target 已更新、materialized 仍是
 * 旧值，返回 {@code false} 让调度器下周期重试，diff 自动只补缺口，无需额外队列。</p>
 */
public final class VoidFitApplier {

    /** 单次 refit 的写入上限（放+拆合计）。 */
    public static final int CELL_BUDGET = 1024;

    private static final BlockState VOID = Blocks.STRUCTURE_VOID.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int PLACE_FLAGS = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE;
    private static final int PLACE_FLAGS_EVICT_FLUID =
            Block.UPDATE_CLIENTS | Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE;
    private static final int REMOVE_FLAGS =
            Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * 对单个载具执行一次 refit：重算目标格、按 materialized 差分应用、写回 tracker。
     * tracker 中尚无条目时自动建账（调度器无须预建，retry 路径同样依赖这一点）。
     *
     * @param verify 为 true 时先核验 materialized 中仍在目标内的格：世界侧已不是
     *               structure_void（爆炸 DESTROY、玩家 replaceable 顶掉等）的从
     *               materialized 除名，交放置路径重放。逐格一次世界读取，只在慢速
     *               自愈周期开启；位姿触发的 refit 应传 false 控制开销。
     * @return {@code false} 表示写入预算耗尽、存在未物化的目标格——调度器需在下一扫描
     *         周期重试本载具（与位姿阈值无关）
     */
    public static boolean refit(ServerLevel level, VoidFitTracker tracker, ServerSubLevel subLevel, boolean verify) {
        VoidFitTracker.VehicleFit fit = tracker.vehicles().computeIfAbsent(subLevel.getUniqueId(),
                id -> {
                    VoidFitTracker.VehicleFit created = new VoidFitTracker.VehicleFit();
                    created.verifyCountdown = SableVoidFit.VERIFY_SCANS;
                    return created;
                });
        LongOpenHashSet newTarget = VoidFitVoxelizer.voxelize(level, subLevel);
        int budget = CELL_BUDGET;
        boolean complete = true;

        if (verify) {
            LongIterator check = fit.materializedCells.iterator();
            while (check.hasNext()) {
                long cell = check.nextLong();
                if (!newTarget.contains(cell)) {
                    continue; // 交给拆除路径
                }
                BlockPos pos = BlockPos.of(cell);
                if (level.hasChunkAt(pos) && !level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
                    check.remove();
                }
            }
        }

        // 先放新增：水密优先于清理旧位置
        LongIterator additions = newTarget.iterator();
        while (additions.hasNext()) {
            long cell = additions.nextLong();
            if (fit.materializedCells.contains(cell)) {
                continue;
            }
            if (budget <= 0) {
                complete = false;
                break;
            }
            if (placeVoid(level, cell)) {
                fit.materializedCells.add(cell);
                budget--;
            }
        }
        // 再拆移出；仍被其他载具 target 的共享格交给对方续管（过继 materialized），
        // 世界侧空位不动——不变式：世界空位 ⇔ 至少一个载具 target 且至少在一位 owner 账上
        UUID self = subLevel.getUniqueId();
        LongIterator removals = fit.materializedCells.iterator();
        while (removals.hasNext()) {
            long cell = removals.nextLong();
            if (newTarget.contains(cell)) {
                continue;
            }
            if (adoptedByOthers(tracker, self, cell)) {
                removals.remove();
                continue;
            }
            if (budget <= 0) {
                complete = false;
                break;
            }
            if (removeVoid(level, cell)) {
                removals.remove();
                budget--;
            } else if (!level.hasChunkAt(BlockPos.of(cell))) {
                // 未加载区块里的空位：保存剥离保证没落盘，重载后世界侧已是空气——
                // 直接销账，不再惦记
                removals.remove();
            } else {
                // 区块已加载但格上不是空位（玩家 replaceable 顶掉后放了真实方块）：
                // 世界侧本就正确，销账避免每轮白迭代
                removals.remove();
            }
        }

        // 预算截断时把未拆完的存量并入 target：保存剥离在下一周期前的窗口期仍覆盖它们
        if (!complete) {
            newTarget.addAll(fit.materializedCells);
        }
        tracker.setTargetCells(subLevel.getUniqueId(), newTarget);
        fit.lastPosition.set(subLevel.logicalPose().position());
        fit.lastOrientation.set(subLevel.logicalPose().orientation());
        // 自愈倒计时只在 verify 轮重置：行驶中载具逐扫描 refit 不得挤掉自愈
        if (verify) {
            fit.verifyCountdown = SableVoidFit.VERIFY_SCANS;
        }
        return complete;
    }

    /**
     * 载具消失：按 materialized 拆除已写入的空位（仍遵守"只拆空位"纪律）。
     * 与 refit 共用 {@link #CELL_BUDGET}：一次拆不完保留 tracker 条目，调用方下轮
     * 再调本方法续拆（gone 检测按"不在 alive 即处理"，天然兼容）。
     */
    public static void removeAll(ServerLevel level, VoidFitTracker tracker, UUID id) {
        VoidFitTracker.VehicleFit fit = tracker.vehicles().get(id);
        if (fit == null) {
            return;
        }
        int budget = CELL_BUDGET;
        LongIterator it = fit.materializedCells.iterator();
        while (it.hasNext() && budget > 0) {
            long cell = it.nextLong();
            if (adoptedByOthers(tracker, id, cell)) {
                it.remove();
                continue;
            }
            if (removeVoid(level, cell) || !level.hasChunkAt(BlockPos.of(cell))) {
                it.remove();
                budget--;
            }
        }
        if (fit.materializedCells.isEmpty()) {
            tracker.removeVehicle(id);
        }
    }

    /**
     * 区块加载补放：target 格中可占据（空气/纯流体）的格补上空位并计入 materialized。
     * 由 {@code ChunkEvent.Load} 驱动；cells 可能分属多个载具，逐格归属回查。
     */
    public static void placeInChunk(ServerLevel level, VoidFitTracker tracker, LongSet cells) {
        for (long cell : cells) {
            if (placeVoid(level, cell)) {
                // 重叠载具的 target 可能同含此格：全数记账，避免一方移走时被误拆
                for (VoidFitTracker.VehicleFit fit : tracker.vehicles().values()) {
                    if (fit.targetCells.contains(cell)) {
                        fit.materializedCells.add(cell);
                    }
                }
            }
        }
    }

    private static boolean placeVoid(ServerLevel level, long cell) {
        BlockPos pos = BlockPos.of(cell);
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState current = level.getBlockState(pos);
        if (current.is(Blocks.STRUCTURE_VOID)) {
            return false;
        }
        // 只驱逐纯流体（LiquidBlock：水/岩浆及同型 mod 流体）。含水方块（海草、海带、
        // 含水楼梯等 waterlogged）getFluidState 非空但本体是真实方块——绝不可顶掉
        boolean pureFluid = current.getBlock() instanceof LiquidBlock;
        if (!current.isAir() && !pureFluid) {
            // 真实方块（含含水方块）：本身挡水，不覆盖
            return false;
        }
        level.setBlock(pos, VOID, pureFluid ? PLACE_FLAGS_EVICT_FLUID : PLACE_FLAGS);
        return true;
    }

    private static boolean removeVoid(ServerLevel level, long cell) {
        BlockPos pos = BlockPos.of(cell);
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        if (!level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
            return false;
        }
        level.setBlock(pos, AIR, REMOVE_FLAGS);
        return true;
    }

    /**
     * 该格仍被其他载具 target 时，把它过继到所有这些载具的 materialized 账上（谁最后
     * 失去 target 谁负责拆），并返回 true——调用方随即从本载具账上摘除、不动世界。
     * 不过继会产生孤儿空位：放置方因对方在管而销账，对方账上却没有它，最终无人拆除。
     */
    private static boolean adoptedByOthers(VoidFitTracker tracker, UUID self, long cell) {
        boolean adopted = false;
        for (Map.Entry<UUID, VoidFitTracker.VehicleFit> entry : tracker.vehicles().entrySet()) {
            if (!entry.getKey().equals(self) && entry.getValue().targetCells.contains(cell)) {
                entry.getValue().materializedCells.add(cell);
                adopted = true;
            }
        }
        return adopted;
    }

    private VoidFitApplier() {
    }
}
