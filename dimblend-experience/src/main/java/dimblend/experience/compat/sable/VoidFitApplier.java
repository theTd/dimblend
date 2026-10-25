package dimblend.experience.compat.sable;

import java.util.Map;
import java.util.UUID;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
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
 *     触发周围方块响应。例外：G6 熔毁开关开启时，"安全柱顶"（正下方同柱下一格仍在目标集内，
 *     见 {@link VoidFitColumnTops}）的熔岩保留不驱逐——留给 {@code SableLavaMelt} 从车顶线
 *     开始浇淋熔穿；下方由实体投影或补的封口格顶着，任一条件不成立（下方悬空/封口未物化/
 *     别的载具把该格当中层格）就照常驱逐。关闭时熔岩与水同被驱逐。</li>
 * <li>拆除仅当当前格仍是 structure_void（可能被玩家 replaceable 顶掉、被爆炸 DESTROY）；
 *     拆除带邻接更新，让旁侧自然水回流（回流写入走既有 G3 有限水链路）。</li>
 * <li>区块未加载的格跳过写入：target 集合保留该格，区块加载后由 tick 补放队列按表
 *     补放（加载事件内只登记不写入）；未加载区块里的空位若随卸载保存，由
 *     {@code ChunkMapVoidFitStripMixin} 兜底不入盘。</li>
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
        boolean lavaMelt = Config.isLoaded() && Config.SABLE_LAVA_MELT.get();
        if (lavaMelt) {
            // G6 封口：体素化只收非空气方块，下方悬空的柱顶（单格柱/间隙/外挑下沿）
            // 若保留熔岩会从完整船壳漏下去——先补一格目标格（正下方）再算柱顶索引
            newTarget.addAll(VoidFitColumnTops.sealCellsFor(newTarget, level.getMinBuildHeight()));
        }
        Long2IntOpenHashMap columnTops = VoidFitColumnTops.of(newTarget);
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

        UUID self = subLevel.getUniqueId();
        // 先放新增：水密优先于清理旧位置。分两轮——先非柱顶（含封口格），再柱顶：
        // 柱顶保留熔岩的判定要求正下方封口格已物化（placeVoid 的世界侧检查），
        // 同一轮里封口格必须先写；预算耗尽则柱顶整轮不写（complete=false 下轮重试），
        // 绝不出现"封口未放、熔岩已保留"的窗口
        LongIterator additions = newTarget.iterator();
        while (additions.hasNext()) {
            long cell = additions.nextLong();
            if (VoidFitColumnTops.isTop(columnTops, cell)) {
                continue; // 留给第二轮
            }
            if (fit.materializedCells.contains(cell)) {
                continue;
            }
            if (budget <= 0) {
                complete = false;
                break;
            }
            if (placeVoid(level, cell, false)) {
                fit.materializedCells.add(cell);
                budget--;
            }
        }
        if (complete) {
            LongIterator tops = newTarget.iterator();
            while (tops.hasNext()) {
                long cell = tops.nextLong();
                if (!VoidFitColumnTops.isTop(columnTops, cell)) {
                    continue;
                }
                if (fit.materializedCells.contains(cell)) {
                    continue;
                }
                if (budget <= 0) {
                    complete = false;
                    break;
                }
                // 保留条件：本车新 target 与所有其他 target 该格的载具都视其为安全
                // 柱顶（多载具取交集，见 isSealedTopForAllVehicles）；世界侧是否
                // 挡熔岩由 placeVoid 复核
                boolean keep = lavaMelt
                        && isSealedTopForAllVehicles(tracker, self, newTarget, columnTops, cell);
                if (placeVoid(level, cell, keep)) {
                    fit.materializedCells.add(cell);
                    budget--;
                }
            }
        }
        // 再拆移出；仍被其他载具 target 的共享格交给对方续管（过继 materialized），
        // 世界侧空位不动——不变式：世界空位 ⇔ 至少一个载具 target 且至少在一位 owner 账上
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
     * 由 tick 补放队列驱动（加载事件里只登记不写入——在区块任务邮箱上下文内同步
     * setBlock 会与 Sable 物理钩子的邻块拉取形成嵌套等锁，
     * 见 docs/sable-void-fit-chunk-deadlock.md）；cells 可能分属多个载具，逐格归属回查。
     *
     * @param budget 单次最多写入格数（与 {@link #CELL_BUDGET} 同量级）
     * @return {@code false} 表示预算耗尽、尚有格子未尝试——调用方需下轮续放
     *         （target 记账先行、materialized 差分续跑的不变式天然兼容）
     */
    public static boolean placeInChunk(ServerLevel level, VoidFitTracker tracker, LongSet cells, int budget) {
        // 两轮：先非柱顶（含封口格），再柱顶——柱顶保留熔岩要求正下方封口格已物化
        // （封口格必在同 (x,z) 柱，必属同一区块、同一 cells 集合）
        for (long cell : cells) {
            if (budget <= 0) {
                return false;
            }
            if (isColumnTopOfAny(tracker, cell)) {
                continue; // 留给第二轮
            }
            if (placeVoid(level, cell, false)) {
                budget--;
                markMaterialized(tracker, cell);
            }
        }
        for (long cell : cells) {
            if (budget <= 0) {
                return false;
            }
            if (!isColumnTopOfAny(tracker, cell)) {
                continue;
            }
            // 多载具取交集：任一 target 该格的载具把它当中层格/悬空柱顶就驱逐
            if (placeVoid(level, cell, isSealedTopOfAll(tracker, cell))) {
                budget--;
                markMaterialized(tracker, cell);
            }
        }
        return true;
    }

    /** 重叠载具的 target 可能同含此格：全数记账，避免一方移走时被误拆。 */
    private static void markMaterialized(VoidFitTracker tracker, long cell) {
        for (VoidFitTracker.VehicleFit fit : tracker.vehicles().values()) {
            if (fit.targetCells.contains(cell)) {
                fit.materializedCells.add(cell);
            }
        }
    }

    /** 该格是否任一仍 target 它的载具的柱顶格（用于"柱顶延后到第二轮"）。 */
    private static boolean isColumnTopOfAny(VoidFitTracker tracker, long cell) {
        for (VoidFitTracker.VehicleFit fit : tracker.vehicles().values()) {
            if (fit.targetCells.contains(cell) && VoidFitColumnTops.isTop(fit.columnTops, cell)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该格对其所有 target 它的载具都是安全柱顶（多载具取交集）。refit 用本车尚未提交的
     * 新 target/新柱顶索引，其他载具用已提交的 target；任一载具把该格当中层格（即该格
     * 低于它的柱顶）或悬空柱顶，都不保留——否则熔岩会落在那个载具的投影里。
     */
    private static boolean isSealedTopForAllVehicles(VoidFitTracker tracker, UUID self,
            LongSet selfTarget, Long2IntMap selfTops, long cell) {
        if (!VoidFitColumnTops.isSealedTop(selfTops, selfTarget, cell)) {
            return false;
        }
        for (Map.Entry<UUID, VoidFitTracker.VehicleFit> entry : tracker.vehicles().entrySet()) {
            if (entry.getKey().equals(self)) {
                continue;
            }
            VoidFitTracker.VehicleFit fit = entry.getValue();
            if (fit.targetCells.contains(cell)
                    && !VoidFitColumnTops.isSealedTop(fit.columnTops, fit.targetCells, cell)) {
                return false;
            }
        }
        return true;
    }

    /** 补放路径口径：所有已提交 target 该格的载具都视其为安全柱顶。 */
    private static boolean isSealedTopOfAll(VoidFitTracker tracker, long cell) {
        boolean targeted = false;
        for (VoidFitTracker.VehicleFit fit : tracker.vehicles().values()) {
            if (!fit.targetCells.contains(cell)) {
                continue;
            }
            targeted = true;
            if (!VoidFitColumnTops.isSealedTop(fit.columnTops, fit.targetCells, cell)) {
                return false;
            }
        }
        return targeted;
    }

    private static boolean placeVoid(ServerLevel level, long cell, boolean sealedTop) {
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
        // G6 配对（2026-10-26 安全柱顶）：熔毁开关开启时，只有"安全柱顶"的熔岩不驱逐
        // ——该格对每个 target 它的载具都是柱顶且正下方同柱下一格也在 target 内
        // （实体投影或补的封口格），且正下方世界格此刻确实挡熔岩。熔岩留在柱顶格
        // 与方块同格接触（SableLavaMelt 的接触扫描），不能下行；柱内更低格照常驱逐
        // 成结构空位（遮挡恢复、舱内不积岩浆、不导流）。任一条件不成立就退回驱逐
        // （安全方向）。保留格不进 materialized，后续每轮 refit 重试并重新判定
        // （无写入、开销可忽略）；下方一旦离开 target（方块熔掉/投影收缩），下一轮
        // 即驱逐，不会把熔岩带进新投影
        if (pureFluid && current.getFluidState().is(FluidTags.LAVA)
                && Config.isLoaded() && Config.SABLE_LAVA_MELT.get()
                && sealedTop && blocksLavaBelow(level, pos)) {
            return false;
        }
        level.setBlock(pos, VOID, pureFluid ? PLACE_FLAGS_EVICT_FLUID : PLACE_FLAGS);
        return true;
    }

    /**
     * 正下方世界格此刻是否挡熔岩：structure_void 或 blocksMotion() 方块。故意保守——
     * 只认这两种，其余（空气/纯流体/无碰撞方块）一律返回 false，让调用方驱逐柱顶
     * 熔岩（安全方向）。依据原版 {@code FlowingFluid#canHoldFluid}
     * （FlowingFluid.java:394-412）：structure_void 在 :408 被显式排除；blocksMotion
     * 为 true 的方块流体不能占据。封口格可能因区块未加载/预算耗尽/压中非流体方块
     * 尚未物化，这一步把几何判定留下的窗口关掉。
     */
    private static boolean blocksLavaBelow(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        if (!level.hasChunkAt(below)) {
            return false;
        }
        BlockState state = level.getBlockState(below);
        return state.is(Blocks.STRUCTURE_VOID) || state.blocksMotion();
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
