package dimblend.experience.compat.lava;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.exploration.RotatingDimension;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 轨道走廊岩浆的载入期结算（M3 / T-10）。
 *
 * <p>问题：rotating 维度走廊附近的岩浆是原版 spring feature 在生成期放下的单格源头
 * （{@code SpringFeature:72-73}），流动本身发生在运行期、按原版节奏（本仓库把它改成下界
 * 口径，10 tick / 步）慢慢铺开；玩家会看到「一个源头慢慢流过来」。用户口径（answers.md
 * 第 5 题）选定「载入期结算到稳态」，不改生成期——生成期没有 {@code Level}，跑不了原版
 * 流体（见 research/E1-lava.md 第 3.3 节）。</p>
 *
 * <p>做法：{@code ChunkEvent.Load} 里只登记 ChunkPos，在 {@code LevelTickEvent.Post} 里
 * 按预算消化——区块任务邮箱上下文里同步写方块会和 Sable 物理钩子互相等锁
 * （docs/sable-void-fit-chunk-deadlock.md），所以事件回调里不入队以外的任何世界操作。</p>
 *
 * <p><b>推进方式：前沿集合，跑到不再增长为止（2026-11-04 重写）。</b> 第一次碰到一个区块时
 * 扫一遍走廊带得到「当前所有岩浆格」（这是种子）；之后每一轮只对**上一轮新产生的**岩浆格
 * 调一记原版 {@link FluidState#tick}，再把它们的 6 个邻居里是岩浆、且从未排过的格子收进下一轮
 * 前沿。前沿空了就说明这一片岩浆不再往新格子里长，也就是流完了。
 *
 * <p><b>为什么不再「固定 8 轮」。</b> 8 轮 = 8 步，而一条扩散链要 20–30 步才铺到轨道
 * （ultraWarm：{@code getDropOff=1}、{@code getSlopeFindDistance=4}，垂直每步 1 格、
 * 水平每步 1 格，结算带高 19 格——`LavaFluid.java:140/155`）。8 步之后剩下的部分按原版
 * 10 tick/步 当着玩家的面流完，实测就是用户报告的「本应被结算但还在流动」。现在改成收敛，
 * 但代价仍然封顶：每 tick 所有活跃区块合计 {@link LavaSettleMath#STEPS_PER_TICK} 步、
 * 单区块合计 {@link LavaSettleMath#STEP_BUDGET} 步。
 *
 * <p><b>为什么不做「整区块重扫每一轮」。</b> 一次整扫在走廊带内约 3.3 千格读；8 轮就是 2.6 万，
 * 而收敛要几十轮。前沿方案每轮只花「tick 若干格 + 每格 6 个邻居」，几十轮的总成本比原来的
 * 8 轮整扫还低。</p>
 *
 * <p><b>为什么不是「scheduleTick(pos, fluid, 0)」</b>：原版 {@code LevelChunkTicks#schedule}
 * 用 {@code ScheduledTick.UNIQUE_TICK_HASH}（pos + 类型）去重，格子上已有 pending tick 时
 * 新的 {@code scheduleTick} 被静默丢弃；而 spring 的生成期 tick 由
 * {@code ProtoChunkTicks#schedule} 以 delay 0 存下、载入即到期。两条叠加的结果是
 * 「只调度一遍」什么也提前不了（详见 T-10 报告）。要真正推进扩散链，只能自己按轮次直接跑
 * 原版 tick。</p>
 *
 * <p>边界：只处理已经加载、且 3×3 邻区块也都已加载的区块——原版流体一步会读/写相邻区块，
 * 3×3 保证这一读一写都落在已加载范围内，绝不替区块系统做加载。方块读取一律走
 * {@code LevelChunk#getFluidState}，不经过 {@code Level#getBlockState}（那条路会 getChunkAt）。
 * 区块探测一律走 {@code ChunkSource#getChunkNow}，不走 {@code getChunk(x, z, FULL, false)}：
 * 后者的 {@code load=false} 只表示「不加 ticket」，主线程上它仍然调用
 * {@code mainThreadProcessor.managedBlock(future::isDone)}，区块没到 FULL 就把这一 tick 堵在
 * 区块生成上。2026-11-04 的 spark 采样里这条路径占服务器线程 32.2%（26.77s / 84s），
 * 其中 29.1% 直接是 {@code Unsafe.park}；{@code getChunkNow} 只查 ChunkHolder 的
 * {@code getChunkIfPresent(FULL)}，没就绪返回 null，不阻塞也不调度生成。</p>
 *
 * <p>待结算队列有上限 {@link #MAX_PENDING_CHUNKS}（满了丢最旧），每次只扫
 * {@link #PROMOTION_SCAN_BUDGET} 个条目（未就绪的轮转到队尾），不会无限堆积。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class LavaSettleOnLoad {

    /** 待结算队列上限：连续跑图时结算追不上加载，超出即丢最旧，绝不无限堆积。 */
    private static final int MAX_PENDING_CHUNKS = 256;

    /** 一次从待结算队列里最多看几个条目：3×3 邻区块没就绪的条目轮转到队尾，下 tick 再看。 */
    private static final int PROMOTION_SCAN_BUDGET = 8;

    /** 相邻格的方向：前沿只沿这 6 个方向生长。 */
    private static final Direction[] DIRECTIONS = Direction.values();

    /** 按维度（只会有 rotating）登记待结算的区块，FIFO。 */
    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> PENDING = new HashMap<>();

    /** 正在结算的区块（每个维度最多 {@link LavaSettleMath#CHUNKS_PER_TICK} 个）。 */
    private static final Map<ResourceKey<Level>, List<SettleJob>> ACTIVE = new HashMap<>();

    /** 调色板级剪枝用：这一层方块里有没有岩浆。 */
    private static final Predicate<BlockState> HAS_LAVA = state -> state.getFluidState().is(FluidTags.LAVA);

    /** 队列打满只记一次，避免刷屏。 */
    private static boolean overflowLogged;

    /** 一个区块的结算进度：种子在前沿里，每轮把前沿换成「新长出来的岩浆格」。 */
    private static final class SettleJob {
        final ChunkPos pos;
        /** 下一轮要推进的岩浆格（打包坐标）；空 = 前沿不再增长 = 流完了。 */
        final LongLinkedOpenHashSet frontier = new LongLinkedOpenHashSet();
        /** 已经排进过前沿的格子，避免「A 发现 B、B 又发现 A」原地打转。 */
        final LongOpenHashSet seen = new LongOpenHashSet();
        /** 走廊带那份种子是否已经扫过。 */
        boolean seeded;
        int passes;
        int steps;
        /** 种子格数，只用于那一行日志。 */
        int seedCells;
        boolean budgetLogged;

        SettleJob(ChunkPos pos) {
            this.pos = pos;
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!Config.isLoaded() || !RotatingDimension.is(level)) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        if (!LavaSettleMath.chunkTouchesBand(pos.z)) {
            return;
        }
        enqueue(PENDING.computeIfAbsent(level.dimension(), key -> new LongLinkedOpenHashSet()),
                pos.toLong());
    }

    /** 入队并守住上限：满了丢最旧（只记一次 debug），任何时候都不无限堆积。 */
    private static void enqueue(LongLinkedOpenHashSet pending, long packed) {
        if (pending.contains(packed)) {
            return;
        }
        if (pending.size() >= MAX_PENDING_CHUNKS) {
            LongIterator oldest = pending.iterator();
            if (oldest.hasNext()) {
                oldest.nextLong();
                oldest.remove();
            }
            if (!overflowLogged) {
                overflowLogged = true;
                DimBlend.LOGGER.debug("[M3] lava settle queue is full ({} chunks); dropping the oldest", MAX_PENDING_CHUNKS);
            }
        }
        pending.add(packed);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!Config.isLoaded() || !RotatingDimension.is(level)) {
            return;
        }
        LongLinkedOpenHashSet pending = PENDING.get(level.dimension());
        List<SettleJob> active = ACTIVE.get(level.dimension());
        if ((pending == null || pending.isEmpty()) && (active == null || active.isEmpty())) {
            return;
        }
        // 命名 profiler 段，/dimblend slowticks 能把这段开销归到本监听器
        level.getProfiler().push("dimblend:lava_settle");
        try {
            if (pending == null) {
                pending = new LongLinkedOpenHashSet();
                PENDING.put(level.dimension(), pending);
            }
            if (active == null) {
                active = new ArrayList<>(LavaSettleMath.CHUNKS_PER_TICK);
                ACTIVE.put(level.dimension(), active);
            }
            advanceActive(level, pending, active);
            promotePending(level, pending, active);
        } finally {
            level.getProfiler().pop();
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.remove(level.dimension());
            ACTIVE.remove(level.dimension());
        }
    }

    /** 每个活跃区块在预算内推进；完成或区块卸载就出队，邻区块暂时没就绪就退回等待队列。 */
    private static void advanceActive(ServerLevel level, LongLinkedOpenHashSet pending, List<SettleJob> active) {
        int budget = LavaSettleMath.stepBudgetFor(active.size());
        for (int i = active.size() - 1; i >= 0; i--) {
            SettleJob job = active.get(i);
            LevelChunk chunk = loadedChunk(level, job.pos);
            if (chunk == null) {
                active.remove(i);
                continue;
            }
            // 还没扫过种子时先做调色板剪枝：结算带里没有岩浆的区块一步都不用走
            if (!job.seeded && !hasLavaInBand(chunk)) {
                active.remove(i);
                continue;
            }
            // 3×3 邻区块没齐就不动：原版流体一步会读/写相邻区块，这里绝不触发加载
            if (!neighbourhoodLoaded(level, job.pos)) {
                active.remove(i);
                enqueue(pending, job.pos.toLong());
                continue;
            }
            if (settle(level, chunk, job, budget)) {
                active.remove(i);
            }
        }
    }

    /**
     * 在预算内把一个区块推进到收敛。
     *
     * @return true 表示这个区块结算结束（前沿不再增长，或步数/轮数用尽）
     */
    private static boolean settle(ServerLevel level, LevelChunk chunk, SettleJob job, int budget) {
        if (!job.seeded) {
            seed(level, chunk, job);
            job.seeded = true;
            if (job.frontier.isEmpty()) {
                return true;
            }
        }
        if (job.passes == 0) {
            // 每区块只打一行：种子规模，便于拿日志对照实机观察
            DimBlend.LOGGER.info("[M3] lava settle: chunk {} seeded {} lava cell(s) near the track",
                    chunk.getPos(), job.seedCells);
        }
        int left = budget;
        while (left > 0 && !job.frontier.isEmpty()) {
            if (job.steps >= LavaSettleMath.STEP_BUDGET
                    || LavaSettleMath.passesLeft(job.passes, LavaSettleMath.PASS_BUDGET) == 0) {
                if (!job.budgetLogged) {
                    job.budgetLogged = true;
                    DimBlend.LOGGER.debug("[M3] lava settle: chunk {} gave up after {} pass(es) / {} step(s)",
                            chunk.getPos(), job.passes, job.steps);
                }
                return true;
            }
            int used = stepFrontier(level, job, Math.min(left, LavaSettleMath.CELL_BUDGET));
            job.passes++;
            job.steps += used;
            left -= Math.max(1, used);
            if (used == 0) {
                // 排到的格子已经不是岩浆了：这一支流停了
                return job.frontier.isEmpty();
            }
        }
        return job.frontier.isEmpty();
    }

    /**
     * 扫一遍走廊带得到种子（只在区块第一次结算时做一次）。
     *
     * <p>调色板级剪枝：这一层 16×16×16 里没有岩浆，就一行都不逐格读（无岩浆零开销）。
     */
    private static void seed(ServerLevel level, LevelChunk chunk, SettleJob job) {
        int minX = chunk.getPos().getMinBlockX();
        int maxX = chunk.getPos().getMaxBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = chunk.getPos().getMaxBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = LavaSettleMath.BAND_MIN_Y; y <= LavaSettleMath.BAND_MAX_Y; y++) {
            int sectionIndex = chunk.getSectionIndex(y);
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                continue;
            }
            if (!sections[sectionIndex].maybeHas(HAS_LAVA)) {
                continue;
            }
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!LavaSettleMath.inBand(y, z)) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    // 读本区块自己的方块，不走 Level#getFluidState（那条路会 getChunkAt 触发加载）
                    if (!chunk.getFluidState(cursor).is(FluidTags.LAVA)) {
                        continue;
                    }
                    long packed = cursor.asLong();
                    if (job.seen.add(packed)) {
                        job.frontier.add(packed);
                        job.seedCells++;
                    }
                }
            }
        }
    }

    /**
     * 推进一轮前沿：最多 {@code cap} 格，每格跑一记原版流体 tick，再把「新长出来的」岩浆格
     * 收进下一轮。返回真正跑了几格。
     *
     * <p>已经 {@link SettleJob#seen} 过的格子不会被再次排进前沿，否则相邻两格会互相发现对方，
     * 前沿永远不空、收敛永远不成立。
     */
    private static int stepFrontier(ServerLevel level, SettleJob job, int cap) {
        List<Long> batch = new ArrayList<>(Math.min(cap, 64));
        LongIterator iterator = job.frontier.iterator();
        while (iterator.hasNext() && batch.size() < cap) {
            long packed = iterator.nextLong();
            iterator.remove();
            batch.add(packed);
        }
        int stepped = 0;
        for (long packed : batch) {
            BlockPos pos = BlockPos.of(packed);
            FluidState fluid = fluidAt(level, pos);
            if (fluid == null || !fluid.is(FluidTags.LAVA)) {
                continue;
            }
            // 与 ServerLevel#tickFluid 同一条调用：原版 FlowingFluid#tick 自己扩散/重算，
            // FlowingFluidTickMixin 照常生效（下界口径的速度常数按这个列走）。
            fluid.tick(level, pos);
            stepped++;
            for (Direction direction : DIRECTIONS) {
                BlockPos next = pos.relative(direction);
                FluidState neighbour = fluidAt(level, next);
                if (neighbour == null || !neighbour.is(FluidTags.LAVA)) {
                    continue;
                }
                if (job.seen.add(next.asLong())) {
                    job.frontier.add(next.asLong());
                }
            }
        }
        return stepped;
    }

    /** 该格的流体状态；所在区块没加载就返回 null（绝不触发加载）。 */
    private static FluidState fluidAt(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = loadedChunk(level, new ChunkPos(pos));
        if (chunk == null) {
            return null;
        }
        return chunk.getFluidState(pos);
    }

    /**
     * 用待结算队列补满并发位（每 tick 最多 {@link LavaSettleMath#CHUNKS_PER_TICK} 个区块）。
     * 3×3 邻区块没就绪的条目先轮转到队尾，下 tick 再看，绝不占着并发位等。
     */
    private static void promotePending(ServerLevel level, LongLinkedOpenHashSet pending, List<SettleJob> active) {
        List<Long> deferred = null;
        int scanned = 0;
        LongIterator iterator = pending.iterator();
        while (active.size() < LavaSettleMath.CHUNKS_PER_TICK && iterator.hasNext()
                && scanned < PROMOTION_SCAN_BUDGET) {
            long packed = iterator.nextLong();
            iterator.remove();
            scanned++;
            ChunkPos pos = new ChunkPos(packed);
            LevelChunk chunk = loadedChunk(level, pos);
            if (chunk == null) {
                continue; // 已卸载：丢
            }
            if (isActive(active, pos)) {
                continue; // 同一区块已在结算中（重载事件与未完成的 job 撞上）
            }
            // 调色板判定必须排在 3×3 之前：轨道正上方那类「整片没有岩浆」的区块占绝大多数，
            // 它们在这里就被丢掉，一次区块查询都不会花在邻居身上。
            if (!hasLavaInBand(chunk)) {
                continue;
            }
            if (!neighbourhoodLoaded(level, pos)) {
                if (deferred == null) {
                    deferred = new ArrayList<>();
                }
                deferred.add(packed);
                continue;
            }
            active.add(new SettleJob(pos));
        }
        if (deferred != null) {
            for (long packed : deferred) {
                enqueue(pending, packed);
            }
        }
    }

    /** 同一区块是否已在并发位里。 */
    private static boolean isActive(List<SettleJob> active, ChunkPos pos) {
        for (SettleJob job : active) {
            if (job.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结算带（y {@link LavaSettleMath#BAND_MIN_Y}..{@link LavaSettleMath#BAND_MAX_Y}）里有没有岩浆。
     *
     * <p>只看每个 section 的调色板（{@code maybeHas}），不读任何方块、不碰区块系统，所以可以排在
     * 3×3 邻区块检查之前：一整个地表区块在这里一步就被淘汰，不再为它付 9 次区块查询。
     */
    private static boolean hasLavaInBand(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int y = LavaSettleMath.BAND_MIN_Y; y <= LavaSettleMath.BAND_MAX_Y; y++) {
            int sectionIndex = chunk.getSectionIndex(y);
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                continue;
            }
            if (sections[sectionIndex].maybeHas(HAS_LAVA)) {
                return true;
            }
        }
        return false;
    }

    /** 3×3 邻区块是否都已加载（不触发加载，也不阻塞）。 */
    private static boolean neighbourhoodLoaded(ServerLevel level, ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (loadedChunk(level, new ChunkPos(pos.x + dx, pos.z + dz)) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 已加载的 FULL 区块；没有就返回 null。
     *
     * <p>必须走 {@code getChunkNow}。{@code getChunk(x, z, FULL, false)} 里的 {@code false}
     * 只表示「不加 ticket」，主线程上它仍然执行
     * {@code mainThreadProcessor.managedBlock(future::isDone)}——区块没到 FULL 时会把整个
     * tick 堵在生成任务上。{@code getChunkNow} 只查 ChunkHolder 的
     * {@code getChunkIfPresent(FULL)}，不阻塞、也不调度生成。
     */
    private static LevelChunk loadedChunk(ServerLevel level, ChunkPos pos) {
        return level.getChunkSource().getChunkNow(pos.x, pos.z);
    }

    private LavaSettleOnLoad() {
    }
}
