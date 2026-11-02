package dimblend.experience.compat.sable;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
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
 * <li>保留的落点由 {@link LavaRestCell} 定（2026-11-03 用户实机缺陷）：柱顶格若是车顶方块
 *     自身那一格，本格照常驱逐成空位、熔岩抬到柱顶 +1（{@code structure_void} 装不住流体，
 *     不抬就"陷进车顶下一格"）；已是车顶表面上方那一格时才留在本格。保留格同时登记进
 *     {@link LavaFlowGuard}，由 {@code FlowingFluidFlowGuardMixin} 拦掉水平扩散。</li>
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
 *
 * <p>熔穿孔封口塞（2026-10-31 用户实机缺陷）：拆除路径对"上一轮已物化、本轮离开投影"
 * 的格，若世界侧正上方（可跨过载具沉降造成的少量空气格）是岩浆、孔口比一格深（正下方
 * 同柱下一格也不在新 target 内）、且该 (x,z) 柱仍有本车目标格，则把该格重新并入
 * newTarget 保成 structure_void 而不拆（判定见 {@link VoidFitMeltSeal}）——岩浆停在
 * 孔口，不会灌进车厢内部空腔，只熔与岩浆直接接触的那层。实心堆叠的熔穿格下方仍有目标格
 * 顶着，不封口，熔岩照旧逐层下降。</p>
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
     * 空落点表（不可变）：本次写入一律驱逐、不保留岩浆——非柱顶格与"未判定保留"的轮次都用它。
     * 保留落点表由 {@link #lavaRestCells} / {@link #placeInChunk} 每轮现建，键存在即"保留"。
     */
    private static final Long2LongMap NO_LAVA_REST = Long2LongMaps.EMPTY_MAP;

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
        // 载具方块自身中心格：与体素化同一趟枚举（只收"自身格"，保守覆盖多收的格不算），
        // 用于判断柱顶格是不是车顶方块自身那一格（LavaRestCell）
        LongOpenHashSet ownBlockCells = new LongOpenHashSet();
        LongOpenHashSet newTarget = VoidFitVoxelizer.voxelize(level, subLevel, ownBlockCells);
        boolean lavaMelt = Config.isLoaded() && Config.SABLE_LAVA_MELT.get();
        UUID self = subLevel.getUniqueId();
        if (!lavaMelt) {
            // 熔毁关闭：没有"保留岩浆"，上一轮登记的闸门全部撤销
            LavaFlowGuard.releaseOwner(self);
        }
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
            if (placeVoid(level, tracker, self, cell, NO_LAVA_REST)) {
                fit.materializedCells.add(cell);
                budget--;
            }
        }
        if (complete) {
            // 保留落点表：本车新 target 与所有其他 target 该格的载具都视其为安全柱顶
            // （多载具取交集，见 isSealedTopForAllVehicles），再按 LavaRestCell 定落点。
            // 在第二轮写入前现建：封口格刚写完，blocksLavaBelow 读到的是本轮世界状态
            Long2LongMap lavaRests = lavaMelt
                    ? lavaRestCells(level, tracker, self, newTarget, columnTops, ownBlockCells)
                    : NO_LAVA_REST;
            if (lavaMelt) {
                // 闸门按几何重算整轮替换：载具开走/投影收缩时旧登记随之释放
                LavaFlowGuard.refresh(level.dimension(), self, lavaRests.values());
            }
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
                if (placeVoid(level, tracker, self, cell, lavaRests)) {
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
            // 熔穿孔封口塞（2026-10-31）：保住空位、岩浆停在孔口。不写世界、不占写入
            // 预算；该格仍在 materialized 账上（世界侧确实还是空位），下一轮 refit 重判
            if (lavaMelt && isMeltThroughHole(level, newTarget, columnTops, cell)) {
                newTarget.add(cell);
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
            // 载具彻底消失：闸门登记整体撤销（保留岩浆格本身留在世界里自然流动）
            LavaFlowGuard.releaseOwner(id);
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
            if (placeVoid(level, tracker, null, cell, NO_LAVA_REST)) {
                budget--;
                markMaterialized(tracker, cell);
            }
        }
        // 保留落点表：补放路径没有载具位姿，拿不到"柱顶格是不是车顶方块自身那一格"，
        // 按落点 = 柱顶格处理（旧口径）。第二轮 refit 会用几何重算整轮替换这张登记表
        Long2LongOpenHashMap lavaRests = new Long2LongOpenHashMap();
        if (Config.isLoaded() && Config.SABLE_LAVA_MELT.get()) {
            for (long cell : cells) {
                if (!isSealedTopOfAll(tracker, cell) || !blocksLavaBelow(level, BlockPos.of(cell))) {
                    continue;
                }
                lavaRests.put(cell, cell);
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
            if (placeVoid(level, tracker, null, cell, lavaRests)) {
                budget--;
                markMaterialized(tracker, cell);
            } else if (isLavaAt(level, cell)) {
                // 保留在本格：闸门按"该格对所有 target 它的载具成立"登记（下一轮 refit 重算替换）
                retainGuardForTargeting(level, tracker, cell);
            }
        }
        return true;
    }

    /** 对每个仍 target 该格的载具登记保留格（补放路径的归属回查，见 {@link #markMaterialized}）。 */
    private static void retainGuardForTargeting(ServerLevel level, VoidFitTracker tracker, long cell) {
        for (Map.Entry<UUID, VoidFitTracker.VehicleFit> entry : tracker.vehicles().entrySet()) {
            if (entry.getValue().targetCells.contains(cell)) {
                LavaFlowGuard.retain(level.dimension(), entry.getKey(), cell);
            }
        }
    }

    /** 该格是否仍是岩浆/岩浆流（与 SableLavaMelt 的接触判定同口径）。 */
    private static boolean isLavaAt(ServerLevel level, long cell) {
        BlockPos pos = BlockPos.of(cell);
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.LAVA);
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

    /**
     * 本轮 target 的"保留岩浆落点表"：柱顶格 → 熔岩静止格，键存在即保留。
     *
     * <p>三条条件都要成立：本车新 target 与所有其他载具都视该格为安全柱顶（多载具取交集，
     * 见 {@link #isSealedTopForAllVehicles}）；正下方世界格此刻挡熔岩（
     * {@link #blocksLavaBelow}）；落点由 {@link LavaRestCell} 按"柱顶格是不是车顶方块自身
     * 那一格"给出——自身格 → 落柱顶 +1（本格驱逐成结构空位），已是车顶表面上方那一格 →
     * 落柱顶格。本表在柱顶写入轮之前现建：封口格刚写完，读到的是本轮世界状态。</p>
     */
    private static Long2LongMap lavaRestCells(ServerLevel level, VoidFitTracker tracker, UUID self,
            LongSet newTarget, Long2IntMap columnTops, LongSet ownBlockCells) {
        Long2LongOpenHashMap rests = new Long2LongOpenHashMap();
        for (long cell : newTarget) {
            if (!VoidFitColumnTops.isTop(columnTops, cell)) {
                continue;
            }
            if (!isSealedTopForAllVehicles(tracker, self, newTarget, columnTops, cell)) {
                continue;
            }
            OptionalLong rest = LavaRestCell.restCell(cell, ownBlockCells.contains(cell),
                    blocksLavaBelow(level, BlockPos.of(cell)));
            if (rest.isPresent()) {
                rests.put(cell, rest.getAsLong());
            }
        }
        return rests;
    }

    /**
     * @param lavaRests 本轮保留落点表（键 = 柱顶格，值 = 熔岩静止格）；不保留时传
     *                  {@link #NO_LAVA_REST}
     * @return 是否写入（写了结构空位 → 调用方计入 materialized；保留熔岩返回 {@code false}）
     */
    private static boolean placeVoid(ServerLevel level, VoidFitTracker tracker, UUID self,
            long cell, Long2LongMap lavaRests) {
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
        // G6 配对（2026-10-26 安全柱顶 / 2026-11-03 落点修正）：只有安全柱顶的熔岩不驱逐
        // ——留给 SableLavaMelt 从车顶线浇淋熔穿。落点按 LavaRestCell 定：柱顶格是车顶方块
        // 自身那一格时本格必须驱逐成空位、熔岩抬到上面一格（structure_void 装不住流体），
        // 否则熔岩会"陷在车顶下一格"；柱顶格已是车顶表面上方那一格时才留在本格。保留格
        // 不进 materialized，后续每轮 refit 重试并重新判定；下方一旦离开 target（方块熔掉/
        // 投影收缩），下一轮即驱逐，不会把熔岩带进新投影
        if (pureFluid && current.getFluidState().is(FluidTags.LAVA) && lavaRests.containsKey(cell)) {
            long rest = lavaRests.get(cell);
            if (rest == cell) {
                return false;
            }
            // 柱顶格是车顶方块自身那一格：本格驱逐成空位，熔岩抬到柱顶 +1
            moveLavaAbove(level, tracker, self, rest, current);
        }
        level.setBlock(pos, VOID, pureFluid ? PLACE_FLAGS_EVICT_FLUID : PLACE_FLAGS);
        return true;
    }

    /**
     * 把车顶方块自身格里的熔岩抬到 {@code rest}（柱顶 +1）。柱顶格马上要被 {@code setBlock}
     * 顶成结构空位，不抬这一格岩浆就被直接销毁、熔毁接触链断掉。
     *
     * <p>只在落点是空气时写：已经是岩浆就原样留着（不重写流动 state）；水/真实方块/
     * 结构空位不动——宁可驱逐这一格岩浆（安全方向），也不往实体里灌岩浆。落点若是其它
     * 载具的 target（重叠载具），同样不搬：岩浆不能陷进别人的壳里。</p>
     */
    private static void moveLavaAbove(ServerLevel level, VoidFitTracker tracker, UUID self,
            long rest, BlockState lavaState) {
        int y = BlockPos.getY(rest);
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            return;
        }
        BlockPos restPos = BlockPos.of(rest);
        if (!level.hasChunkAt(restPos)) {
            return;
        }
        if (self != null && isTargetedByOtherVehicle(tracker, self, rest)) {
            return;
        }
        if (!level.getBlockState(restPos).isAir()) {
            return;
        }
        level.setBlock(restPos, lavaState, PLACE_FLAGS_EVICT_FLUID);
    }

    /** 该格是否被除 {@code self} 之外的载具 target（重叠载具的空间不得灌岩浆）。 */
    private static boolean isTargetedByOtherVehicle(VoidFitTracker tracker, UUID self, long cell) {
        for (Map.Entry<UUID, VoidFitTracker.VehicleFit> entry : tracker.vehicles().entrySet()) {
            if (!entry.getKey().equals(self) && entry.getValue().targetCells.contains(cell)) {
                return true;
            }
        }
        return false;
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

    /**
     * 该格是否是熔穿孔的孔口（世界侧仍是结构空位 + {@link VoidFitMeltSeal} 的三条边界）。
     * 只读世界、不写；世界侧已不是空位时返回 false，交给既有拆除/销账路径。
     */
    private static boolean isMeltThroughHole(ServerLevel level, LongSet newTarget,
            Long2IntMap columnTops, long cell) {
        BlockPos pos = BlockPos.of(cell);
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
            return false;
        }
        return VoidFitMeltSeal.shouldSeal(lavaAbovePlug(level, pos, newTarget), columnTops, newTarget, cell);
    }

    /** 封口塞与液面之间允许跨过的空气格数（载具在岩浆下微沉时保守覆盖整体下移一格）。 */
    private static final int LAVA_PLUG_GAP_CELLS = 3;

    /**
     * 该格正上方是否是岩浆：向上跳过空气格，遇到第一个非空气格判定（岩浆 → 是；
     * 目标空位/真实方块 → 岩浆不可能在它上面，否）。带 {@code hasChunkAt} 守卫，
     * 未加载按否——安全方向：不为读不到的区块保留空位。
     *
     * <p>为什么要跨空气格（2026-10-31）：载具在岩浆下微微沉降时，保守覆盖（{
     * floor(中心 ± 半 extent)}）会整体下移一格，孔口塞与液面之间出现 1-2 格空气。
     * 只看正上方一格会把塞子当成"上方没有岩浆"拆掉，岩浆随即灌进空腔——正是本缺陷
     * 的复现路径。跳过空气格后塞子继续成立，岩浆落下来停在塞子上。</p>
     */
    private static boolean lavaAbovePlug(ServerLevel level, BlockPos pos, LongSet newTarget) {
        BlockPos.MutableBlockPos above = pos.mutable();
        for (int dy = 1; dy <= LAVA_PLUG_GAP_CELLS; dy++) {
            above.set(pos.getX(), pos.getY() + dy, pos.getZ());
            if (!level.hasChunkAt(above)) {
                return false;
            }
            if (newTarget.contains(above.asLong())) {
                return false; // 目标空位挡在中间：岩浆不可能位于它之上
            }
            BlockState state = level.getBlockState(above);
            if (state.isAir()) {
                continue;
            }
            return state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.LAVA);
        }
        return false;
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
        // 空位拆除 = 载具离开/投影收缩：压在它上面那一格的保留岩浆不再受闸门约束，
        // 立即解除（不等下一轮 refit；静止载具的 refit 最长可隔一个自愈周期）
        LavaFlowGuard.releaseCell(level.dimension(), BlockPos.asLong(
                pos.getX(), pos.getY() + 1, pos.getZ()));
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
