package dimblend.experience.compat.cdg;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.create.KineticLedgerTrace;
import dimblend.experience.compat.create.KineticNetworkLedgerTrace;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.mixin.compat.create.KineticNetworkUnloadedAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 柴油机"误判过载→爆机"诊断探针（只读，不改任何行为；配置 {@code dieselOverloadProbe}）。
 * 所有输出以 {@code [CDG-PROBE]} 开头，同一次疑似过载共用 {@code #编号}，便于 grep。
 *
 * <p>一次"疑似过载"（episode）= 引擎被检查时 Create 缓存的 {@code overStressed} 为真，直到缓存位恢复为假：
 * <ul>
 * <li>{@code BEGIN}：开始时的完整快照——所在维度/坐标/是否在 Sable 子层级、BE 已存活 tick 数、
 * 网络 id/initialized/成员数/源数/未加载份额、实时容量与应力、是否判为真过载；真过载时附成员逐项明细
 * （按应力降序，标出区块未加载、已 removed、与世界中 BE 不是同一实例"陈旧"、同坐标重复）
 * 以及该网络最近的账本事件回放（INIT/ADD_SILENTLY/ADD/REMOVE/SYNC…，见
 * {@code KineticNetworkLedgerTraceMixin}）；</li>
 * <li>{@code TICK}：容量/应力/成员数/判定变化时一行（每次至多 {@value #MAX_CHANGE_LINES} 行，之后每 100 tick 心跳）；</li>
 * <li>{@code END}：缓存位恢复，附持续 tick 数与被计入确认的 tick 数；</li>
 * <li>{@code FUSE_START}（WARN）：确认通过、即将点引信时的完整转储（快照+明细+事件回放+本 episode 逐 tick 采样）。</li>
 * </ul>
 * 读法：BEGIN 里 {@code counted=false} = 粘住的缓存位（实时盖得住，已被刷掉）；{@code counted=true}
 * 时看 {@code live.cap} 与 {@code live.stress} 的差，再看明细里 stale/unloadedChunk/dupPos 与回放里
 * {@code ADD_SILENTLY flag=true}（重复入网）、{@code REMOVE flag=false}（移除了非成员）。</p>
 */
public final class CdgOverloadProbe {

    private static final String TAG = "[CDG-PROBE]";
    private static final int MAX_CHANGE_LINES = 30;
    private static final int HEARTBEAT_TICKS = 100;
    private static final int SAMPLE_CAPACITY = 60;
    private static final int TOP_MEMBERS = 12;
    private static final int MAX_SOURCES_LISTED = 12;
    private static final int TRACE_FULL = 32;
    private static final int TRACE_BRIEF = 6;

    private static final AtomicInteger EPISODE_SEQ = new AtomicInteger();
    private static final Map<BlockEntity, ProbeState> STATES = new WeakHashMap<>();

    private static final class ProbeState {
        long firstSeenTick = -1;
        boolean inEpisode;
        int episodeId;
        long episodeStartTick;
        long lastObservedTick;
        int episodeTicks;
        int countedTicks;
        int changeLines;
        float lastCapacity;
        float lastStress;
        int lastSize;
        boolean lastCounted;
        final long[] sampleTick = new long[SAMPLE_CAPACITY];
        final float[] sampleCapacity = new float[SAMPLE_CAPACITY];
        final float[] sampleStress = new float[SAMPLE_CAPACITY];
        final int[] sampleSize = new int[SAMPLE_CAPACITY];
        final boolean[] sampleCounted = new boolean[SAMPLE_CAPACITY];
        int sampleNext;
        int sampleCount;
    }

    private CdgOverloadProbe() {
    }

    private static boolean enabled() {
        return Config.DIESEL_OVERLOAD_PROBE.get();
    }

    private static long serverTick(Level level) {
        return level.getServer() == null ? -1L : level.getServer().getTickCount();
    }

    private static ProbeState stateOf(BlockEntity be, long tick) {
        ProbeState s = STATES.computeIfAbsent(be, k -> new ProbeState());
        if (s.firstSeenTick < 0) {
            s.firstSeenTick = tick;
        }
        return s;
    }

    /** 引擎每个服务端 tick 调一次（在任何早退之前），用来量"BE 本次加载后已存活多少 tick"。 */
    public static void touch(BlockEntity engine) {
        if (!(engine.getLevel() instanceof ServerLevel level) || !enabled()) {
            return;
        }
        stateOf(engine, serverTick(level));
    }

    /**
     * 缓存位为假（或无网络）：若有进行中的 episode 则收尾。
     * 由 {@link CdgKineticOverload#refreshedOverstressed} 在早退分支调用。
     */
    public static void observeClear(KineticBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level) || !enabled()) {
            return;
        }
        ProbeState s = STATES.get(be);
        if (s == null || !s.inEpisode) {
            return;
        }
        endEpisode(level, be, s, "cachedBitFalse hasNetwork=" + be.hasNetwork());
    }

    /**
     * 缓存位为真时每次复核调用一次。
     *
     * @param counted 复核结论：true=实时仍过载（会计入确认计数），false=粘住的缓存位（已被刷掉）
     */
    public static void observeSuspect(KineticBlockEntity be, KineticNetwork network,
            float capacity, float stress, boolean counted) {
        if (!(be.getLevel() instanceof ServerLevel level) || !enabled()) {
            return;
        }
        long tick = serverTick(level);
        ProbeState s = stateOf(be, tick);
        int size = network.getSize();
        if (!s.inEpisode) {
            s.inEpisode = true;
            s.episodeId = EPISODE_SEQ.incrementAndGet();
            s.episodeStartTick = tick;
            s.episodeTicks = 0;
            s.countedTicks = 0;
            s.changeLines = 0;
            s.sampleNext = 0;
            s.sampleCount = 0;
            s.lastCapacity = capacity;
            s.lastStress = stress;
            s.lastSize = size;
            s.lastCounted = counted;
            DimBlend.LOGGER.info("{} #{} BEGIN counted={} {}", TAG, s.episodeId, counted,
                    describe(level, be, network, capacity, stress, s));
            if (counted) {
                dumpDetail(level, be, network, s.episodeId, TRACE_FULL);
            } else {
                dumpTrace(network, s.episodeId, TRACE_BRIEF);
            }
        } else {
            boolean changed = capacity != s.lastCapacity || stress != s.lastStress
                    || size != s.lastSize || counted != s.lastCounted;
            boolean heartbeat = s.episodeTicks > 0 && s.episodeTicks % HEARTBEAT_TICKS == 0;
            if ((changed && s.changeLines < MAX_CHANGE_LINES) || heartbeat) {
                s.changeLines += changed ? 1 : 0;
                DimBlend.LOGGER.info("{} #{} TICK +{} counted={} live[cap={},stress={}] size={} changed={}",
                        TAG, s.episodeId, tick - s.episodeStartTick, counted, capacity, stress, size, changed);
            }
            s.lastCapacity = capacity;
            s.lastStress = stress;
            s.lastSize = size;
            s.lastCounted = counted;
        }
        s.lastObservedTick = tick;
        s.episodeTicks++;
        if (counted) {
            s.countedTicks++;
        }
        s.sampleTick[s.sampleNext] = tick;
        s.sampleCapacity[s.sampleNext] = capacity;
        s.sampleStress[s.sampleNext] = stress;
        s.sampleSize[s.sampleNext] = size;
        s.sampleCounted[s.sampleNext] = counted;
        s.sampleNext = (s.sampleNext + 1) % SAMPLE_CAPACITY;
        if (s.sampleCount < SAMPLE_CAPACITY) {
            s.sampleCount++;
        }
    }

    /**
     * 确认通过、即将点引信：WARN 级完整转储。
     *
     * @param engine     引擎方块实体（普通/组合式即 be 本身；巨型机为引擎，be 为其轴）
     * @param be         被复核的 Kinetic 方块实体（普通/组合式=引擎，巨型机=轴）
     * @param engineInfo 调用方拼好的引擎侧信息（燃油/enabled/throttle/爬梯状态等）
     */
    public static void fuseStart(BlockEntity engine, KineticBlockEntity be, String engineInfo) {
        if (!(be.getLevel() instanceof ServerLevel level) || !enabled()) {
            return;
        }
        ProbeState s = STATES.get(be);
        int id = s == null || !s.inEpisode ? EPISODE_SEQ.incrementAndGet() : s.episodeId;
        long tick = serverTick(level);
        ProbeState engineState = STATES.get(engine);
        long engineAge = engineState == null || engineState.firstSeenTick < 0 ? -1 : tick - engineState.firstSeenTick;
        KineticNetwork network = be.getOrCreateNetwork();
        float capacity = network.calculateCapacity();
        float stress = network.calculateStress();
        DimBlend.LOGGER.warn("{} #{} FUSE_START engine={}@{} engineAgeTicks={} episodeTicks={} countedTicks={} | {} | {}",
                TAG, id, engine.getClass().getSimpleName(), engine.getBlockPos().toShortString(), engineAge,
                s == null ? -1 : s.episodeTicks, s == null ? -1 : s.countedTicks, engineInfo,
                describe(level, be, network, capacity, stress, s));
        dumpDetail(level, be, network, id, TRACE_FULL);
        if (s != null) {
            StringBuilder sb = new StringBuilder();
            int n = s.sampleCount;
            int start = (s.sampleNext - n + SAMPLE_CAPACITY) % SAMPLE_CAPACITY;
            for (int i = 0; i < n; i++) {
                int k = (start + i) % SAMPLE_CAPACITY;
                sb.append("\n    t=").append(s.sampleTick[k])
                        .append(" cap=").append(s.sampleCapacity[k])
                        .append(" stress=").append(s.sampleStress[k])
                        .append(" size=").append(s.sampleSize[k])
                        .append(" counted=").append(s.sampleCounted[k]);
            }
            DimBlend.LOGGER.warn("{} #{} episode samples (last {}, old->new):{}", TAG, id, n, sb);
        }
    }

    private static void endEpisode(ServerLevel level, KineticBlockEntity be, ProbeState s, String reason) {
        long tick = serverTick(level);
        DimBlend.LOGGER.info("{} #{} END reason={} duration={}t observedTicks={} countedTicks={} gapSinceLastObserve={}t {}@{}",
                TAG, s.episodeId, reason, tick - s.episodeStartTick, s.episodeTicks, s.countedTicks,
                tick - s.lastObservedTick, be.getClass().getSimpleName(), be.getBlockPos().toShortString());
        s.inEpisode = false;
    }

    private static String describe(ServerLevel level, KineticBlockEntity be, KineticNetwork network,
            float capacity, float stress, ProbeState s) {
        BlockPos pos = be.getBlockPos();
        BlockPos world = SableWorldPosition.projectBlock(level, pos);
        KineticNetworkUnloadedAccessor ledger = (KineticNetworkUnloadedAccessor) network;
        long tick = serverTick(level);
        long age = s == null || s.firstSeenTick < 0 ? -1 : tick - s.firstSeenTick;
        return "tick=" + tick + " dim=" + level.dimension().location()
                + " be=" + be.getClass().getSimpleName() + "@" + pos.toShortString()
                + " world=" + world.toShortString() + " inSubLevel=" + !world.equals(pos)
                + " beAgeTicks=" + age
                + " net=#" + network.id + " init=" + network.initialized
                + " size=" + network.getSize() + " members=" + network.members.size()
                + " sources=" + network.sources.size()
                + " unloaded{cap=" + ledger.dimblend$unloadedCapacity()
                + ",stress=" + ledger.dimblend$unloadedStress()
                + ",members=" + ledger.dimblend$unloadedMembers() + "}"
                + " live{cap=" + capacity + ",stress=" + stress + "}"
                + " cachedOverstressed=" + be.isOverStressed()
                + " selfMember=" + network.members.containsKey(be)
                + " selfSource=" + network.sources.containsKey(be)
                + " speed=" + be.getSpeed() + " theoretical=" + be.getTheoreticalSpeed()
                + " generated=" + be.getGeneratedSpeed()
                + " hasSource=" + be.hasSource() + " networkDirty=" + be.networkDirty;
    }

    private static void dumpTrace(KineticNetwork network, int id, int limit) {
        KineticLedgerTrace trace = ((KineticNetworkLedgerTrace) network).dimblend$ledgerTrace();
        List<KineticLedgerTrace.Event> events = trace.recent(limit);
        StringBuilder sb = new StringBuilder();
        for (KineticLedgerTrace.Event e : events) {
            sb.append("\n    ").append(e.format());
        }
        DimBlend.LOGGER.info("{} #{} network ledger events (total {}, showing last {}, old->new):{}",
                TAG, id, trace.total(), events.size(), sb);
    }

    private static void dumpDetail(ServerLevel level, KineticBlockEntity self, KineticNetwork network, int id,
            int traceLimit) {
        record Row(KineticBlockEntity be, float stress, String flags) {
        }
        List<Row> rows = new ArrayList<>();
        Map<BlockPos, Integer> posCount = new HashMap<>();
        int notLoaded = 0;
        int removed = 0;
        int stale = 0;
        int noLevel = 0;
        float sumStress = 0.0F;
        for (KineticBlockEntity m : network.members.keySet()) {
            float st = network.getActualStressOf(m);
            sumStress += st;
            posCount.merge(m.getBlockPos().immutable(), 1, Integer::sum);
            StringBuilder flags = new StringBuilder();
            Level ml = m.getLevel();
            if (ml == null) {
                noLevel++;
                flags.append(" noLevel");
            } else if (!ml.isLoaded(m.getBlockPos())) {
                notLoaded++;
                flags.append(" chunkUnloaded");
            } else if (ml.getBlockEntity(m.getBlockPos()) != m) {
                stale++;
                flags.append(" STALE(not the BE in world)");
            }
            if (m.isRemoved()) {
                removed++;
                flags.append(" removed");
            }
            rows.add(new Row(m, st, flags.toString()));
        }
        int dupPos = 0;
        for (int c : posCount.values()) {
            if (c > 1) {
                dupPos += c - 1;
            }
        }
        rows.sort((a, b) -> Float.compare(b.stress, a.stress));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(TOP_MEMBERS, rows.size()); i++) {
            Row r = rows.get(i);
            sb.append("\n    ").append(r.be.getClass().getSimpleName()).append('@')
                    .append(r.be.getBlockPos().toShortString())
                    .append(" stress=").append(r.stress)
                    .append(" speed=").append(r.be.getSpeed())
                    .append(" theoretical=").append(r.be.getTheoreticalSpeed())
                    .append(" lastStressApplied=").append(r.be.calculateStressApplied())
                    .append(r.flags);
        }
        DimBlend.LOGGER.info("{} #{} members: n={} sumActualStress={} chunkUnloaded={} removed={} stale={} noLevel={} dupPos={} top{}:{}",
                TAG, id, rows.size(), sumStress, notLoaded, removed, stale, noLevel, dupPos,
                Math.min(TOP_MEMBERS, rows.size()), sb);

        StringBuilder src = new StringBuilder();
        float sumCap = 0.0F;
        int listed = 0;
        for (KineticBlockEntity s : network.sources.keySet()) {
            float cap = network.getActualCapacityOf(s);
            sumCap += cap;
            if (listed++ < MAX_SOURCES_LISTED) {
                Level sl = s.getLevel();
                String loaded = sl == null ? " noLevel"
                        : !sl.isLoaded(s.getBlockPos()) ? " chunkUnloaded"
                                : sl.getBlockEntity(s.getBlockPos()) != s ? " STALE(not the BE in world)" : "";
                src.append("\n    ").append(s.getClass().getSimpleName()).append('@')
                        .append(s.getBlockPos().toShortString())
                        .append(" capacity=").append(cap)
                        .append(" generated=").append(s.getGeneratedSpeed())
                        .append(" speed=").append(s.getSpeed())
                        .append(" overStressed=").append(s.isOverStressed())
                        .append(s.isRemoved() ? " removed" : "")
                        .append(loaded);
            }
        }
        DimBlend.LOGGER.info("{} #{} sources: n={} sumActualCapacity={}{}", TAG, id,
                network.sources.size(), sumCap, src);
        dumpTrace(network, id, traceLimit);
    }
}
