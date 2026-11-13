package dimblend.experience.compat.sable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

/**
 * 结构空位拟合调度入口（G3 配套）：rotating 维度内让 Sable 载具的世界投影体积始终被
 * {@code minecraft:structure_void} 顶住，水不会流进"船体内部"（原版
 * {@code FlowingFluid#canHoldFluid} 硬编码排除 structure_void）。
 *
 * <p>节律与预算（性能约束全在本类收口）：</p>
 * <ul>
 * <li>每 {@code Config#SABLE_VOID_FIT_REFIT_TICKS} tick 扫描一次（默认 5，即 4Hz）；</li>
 * <li>单次扫描最多 refit {@code Config#SABLE_VOID_FIT_SCAN_BUDGET} 个载具（默认 2），
 *     候选按 重试优先（FIFO 轮转）→ 新载具 → 位姿超阈值 → 自愈到期 排序，超出预算的
 *     留到下轮（round-robin 由 {@code LinkedHashSet} 重试队列承载）；</li>
 * <li>位姿阈值：平移 {@value #TRANSLATION_THRESHOLD} 格或旋转约
 *     {@value #ROTATION_THRESHOLD_DEG}° 才触发重算，静止载具每轮只有 O(载具数) 次
 *     位姿比较；</li>
 * <li>慢速自愈：每 {@link #VERIFY_SCANS} 个扫描周期强制 refit 一次并开启逐格核验，
 *     吸收爆炸/玩家顶掉等外部漂移。</li>
 * <li>区块加载补放走待消化队列：{@code ChunkEvent.Load} 里只登记 ChunkPos，
 *     在 tick 扫描里按 {@link #CHUNK_BUDGET_PER_SCAN} 个/轮的预算消化——在区块任务
 *     邮箱上下文内同步 setBlock 会与 Sable 物理钩子的邻块拉取形成嵌套等锁
 *     （docs/sable-void-fit-chunk-deadlock.md 事故记录）。</li>
 * </ul>
 *
 * <p>注册走 {@code DimBlend} 构造器的 Sable 门控（{@code ModList.isLoaded("sable")} 后
 * 才解析本类），与 {@link SableWorldPosition} 同款惰性解析纪律。</p>
 *
 * <p>已知限制：Create 装配体/蓝图若把拟合空位圈进 contraption，空位会随组装落进
 * contraption 存储、重新放置时成为不受 tracker 管理且会正常落盘的 structure_void——
 * 需装配范围与行驶载具重叠，极小众，按接受处理。结构方块模板捕获空位则无害
 * （模板语义下 structure_void = 加载时跳过）。</p>
 */
public final class SableVoidFit {

    /** 慢速自愈周期（按扫描次数计：20 扫描 × 默认 5 tick = 100 tick）。 */
    public static final int VERIFY_SCANS = 20;
    private static final double TRANSLATION_THRESHOLD = 0.5;
    private static final double ROTATION_THRESHOLD_DEG = 2.5;
    private static final double TRANSLATION_THRESHOLD_SQR = TRANSLATION_THRESHOLD * TRANSLATION_THRESHOLD;
    /** |q1·q2| 下限：四元数点积偏离 1 超过 ~2.5° 视为转动。 */
    private static final double ROTATION_DOT_MIN =
            Math.cos(Math.toRadians(ROTATION_THRESHOLD_DEG));

    /** 上次 refit 因预算未写完、下轮必须重试的载具（按维度，FIFO 轮转）。 */
    private static final Map<ResourceKey<Level>, Set<UUID>> RETRY = new HashMap<>();

    /** 区块加载补放的待消化区块（按维度，ChunkPos.toLong，FIFO）。 */
    private static final Map<ResourceKey<Level>, LongSet> PENDING_CHUNKS = new HashMap<>();
    /** 单次扫描最多消化的待补放区块数。 */
    private static final int CHUNK_BUDGET_PER_SCAN = 2;
    /** 空 target 连续重试上限（≈一个自愈周期）：超过则退回自愈节拍，防病态载具挤占预算。 */
    private static final int EMPTY_TARGET_RETRY_LIMIT = 20;

    /** 由 DimBlend 构造器在 Sable 在场时调用（只此一条入口）。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableVoidFit::onLevelTick);
        NeoForge.EVENT_BUS.addListener(SableVoidFit::onChunkLoad);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        // Named profiler section, so /dimblend slowticks can attribute this listener.
        event.getLevel().getProfiler().push("dimblend:void_fit");
        try {
            tickLevel(event);
        } finally {
            event.getLevel().getProfiler().pop();
        }
    }

    private static void tickLevel(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.isLoaded() || !Config.SABLE_STRUCTURE_VOID_FIT.get()) {
            return;
        }
        int interval = Config.SABLE_VOID_FIT_REFIT_TICKS.get();
        if (level.getGameTime() % interval != 0) {
            return;
        }
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        VoidFitTracker tracker = VoidFitTracker.of(level.dimension());
        Set<UUID> retry = RETRY.computeIfAbsent(level.dimension(), key -> new LinkedHashSet<>());

        List<ServerSubLevel> current = container.getAllSubLevels();
        Map<UUID, ServerSubLevel> aliveById = new HashMap<>(current.size());
        for (ServerSubLevel subLevel : current) {
            if (!subLevel.isRemoved()) {
                aliveById.put(subLevel.getUniqueId(), subLevel);
            }
        }
        // 载具消失（拆除/跨维度/驶出）：按 materialized 拆掉已写入的空位。
        // 先收集再处理——removeAll 会改动 tracker.vehicles，不能边遍历边删。
        // removeAll 受写入预算限制，拆不完的条目保留在 tracker，下轮扫描自然续拆
        List<UUID> gone = new ArrayList<>();
        for (UUID id : tracker.vehicles().keySet()) {
            if (!aliveById.containsKey(id)) {
                gone.add(id);
            }
        }
        for (UUID id : gone) {
            VoidFitApplier.removeAll(level, tracker, id);
            retry.remove(id);
        }
        // 已在 retry 里但已从世界消失的 id 清掉（deferred 的新载具在下次扫描前被拆的情形）
        retry.removeIf(id -> !aliveById.containsKey(id));

        int vehicleBudget = Config.SABLE_VOID_FIT_SCAN_BUDGET.get();
        Set<UUID> processed = new HashSet<>();
        List<ServerSubLevel> deferred = new ArrayList<>();
        // 重试集合优先（按 LinkedHashSet FIFO 出队，未写完排回队尾，避免编组列车尾部车厢饥饿）；
        // 常规预算重试不再强制开启逐格世界核验，只有自愈倒计时归零时才传 verify=true。
        if (!retry.isEmpty() && vehicleBudget > 0) {
            List<VoidFitRetryQueue.RetryCandidate> dueRetries =
                    VoidFitRetryQueue.pollCandidates(tracker, retry, vehicleBudget);
            for (VoidFitRetryQueue.RetryCandidate candidate : dueRetries) {
                ServerSubLevel subLevel = aliveById.get(candidate.id());
                if (subLevel == null) {
                    continue;
                }
                vehicleBudget--;
                processed.add(candidate.id());
                boolean complete = VoidFitApplier.refit(level, tracker, subLevel, candidate.verifyDue());
                VoidFitRetryQueue.finishCandidate(
                        tracker, retry, candidate.id(), complete, EMPTY_TARGET_RETRY_LIMIT);
            }
        }
        for (ServerSubLevel subLevel : current) {
            UUID id = subLevel.getUniqueId();
            if (subLevel.isRemoved() || processed.contains(id) || retry.contains(id)) {
                continue;
            }
            VoidFitTracker.VehicleFit fit = tracker.vehicles().get(id);
            boolean isNew = fit == null;
            boolean verifyDue = !isNew && --fit.verifyCountdown <= 0;
            if (!isNew && !verifyDue && !movedEnough(fit, subLevel)) {
                continue;
            }
            if (vehicleBudget <= 0) {
                deferred.add(subLevel);
                continue;
            }
            vehicleBudget--;
            if (!VoidFitApplier.refit(level, tracker, subLevel, verifyDue)) {
                retry.add(id);
                continue;
            }
            VoidFitRetryQueue.requeueIfTargetMissing(tracker, retry, id, EMPTY_TARGET_RETRY_LIMIT);
        }
        // 超预算的候选下轮优先（与位姿阈值无关）
        for (ServerSubLevel subLevel : deferred) {
            retry.add(subLevel.getUniqueId());
        }
        // 区块加载补放队列按预算消化（离开区块任务邮箱上下文，死锁口径见类头注）。
        // 按新鲜 target 重查：载具已迁走的条目直接丢弃；预算耗尽没放完的回队尾续放
        LongSet pending = PENDING_CHUNKS.get(level.dimension());
        if (pending != null && !pending.isEmpty()) {
            int chunkBudget = CHUNK_BUDGET_PER_SCAN;
            List<Long> requeue = new ArrayList<>();
            LongIterator it = pending.iterator();
            while (it.hasNext() && chunkBudget > 0) {
                long chunkLong = it.nextLong();
                it.remove();
                LongSet cells = tracker.targetCellsOfChunk(new ChunkPos(chunkLong));
                if (cells == null || cells.isEmpty()) {
                    continue;
                }
                chunkBudget--;
                if (!VoidFitApplier.placeInChunk(level, tracker, cells, VoidFitApplier.CELL_BUDGET)) {
                    requeue.add(chunkLong);
                }
            }
            pending.addAll(requeue);
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.isLoaded() || !Config.SABLE_STRUCTURE_VOID_FIT.get()) {
            return;
        }
        VoidFitTracker tracker = VoidFitTracker.peek(level.dimension());
        if (tracker == null) {
            return;
        }
        LongSet cells = tracker.targetCellsOfChunk(event.getChunk().getPos());
        if (cells == null || cells.isEmpty()) {
            return;
        }
        // 只登记不写入：事件在区块任务邮箱的执行上下文里触发，此处同步 setBlock 会被
        // Sable 物理钩子拽去嵌套等邻块形成死锁（docs/sable-void-fit-chunk-deadlock.md），
        // 补放由 onLevelTick 的扫描周期按预算消化
        PENDING_CHUNKS.computeIfAbsent(level.dimension(), key -> new LongLinkedOpenHashSet())
                .add(event.getChunk().getPos().toLong());
    }

    private static boolean movedEnough(VoidFitTracker.VehicleFit fit, ServerSubLevel subLevel) {
        Vector3dc position = subLevel.logicalPose().position();
        if (fit.lastPosition.distanceSquared(position.x(), position.y(), position.z())
                > TRANSLATION_THRESHOLD_SQR) {
            return true;
        }
        Quaterniondc orientation = subLevel.logicalPose().orientation();
        double dot = Math.abs(fit.lastOrientation.dot(orientation));
        return dot < ROTATION_DOT_MIN;
    }

    private SableVoidFit() {
    }
}
