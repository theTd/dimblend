package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.ChassisRailLock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * E8 车架随机横向力的 per-Sable 抽签调度器（全维度，服务端权威）。
 *
 * <p>每个 {@code ServerSubLevel}（一个 Sable 载具/车厢）统一维护一个抽签倒计时：
 * 每次间隔到期时仅从该结构内所有高速（&gt;4 m/s）启用车架中均等随机挑选 1 个抽取一次；
 * 触发后的 {@value TrainLateralForceMath#PUSH_DURATION_TICKS} tick 横向脉冲只作用在被抽中的那一台车架上。</p>
 */
public final class TrainLateralShakeScheduler {

    private record BogeySample(ServerSubLevel subLevel, double absSpeed, boolean active, long gameTime) {
    }

    private record ActivePulse(UUID subLevelId, Direction.Axis axis, double sign, int remainingTicks) {
    }

    private static final class LevelShakeState {
        final Map<PhysicsBogeyBlockEntity, BogeySample> samples = new WeakHashMap<>();
        final Map<UUID, TrainLateralForceMath.SubLevelDrawState> drawStates = new HashMap<>();
    }

    private static final Map<PhysicsBogeyBlockEntity, ActivePulse> ACTIVE_PULSES = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, LevelShakeState> LEVELS = new IdentityHashMap<>();

    private TrainLateralShakeScheduler() {
    }

    /** 仅在 Simurail 在场时注册到 NeoForge 事件总线。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(TrainLateralShakeScheduler::onLevelPostTick);
        NeoForge.EVENT_BUS.addListener(TrainLateralShakeScheduler::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(TrainLateralShakeScheduler::onServerStopped);
    }

    /** 由转向架服务端 {@code tick()} 上报本 tick 采样（仅写服务端状态，不做抽签）。 */
    public static void onBogeyServerTick(PhysicsBogeyBlockEntity bogey, double absSpeed, boolean active) {
        if (!(bogey.getLevel() instanceof ServerLevel level) || !Config.isLoaded()) {
            return;
        }
        if (!(Sable.HELPER.getContaining(bogey) instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) {
            return;
        }
        LEVELS.computeIfAbsent(level, key -> new LevelShakeState())
                .samples
                .put(bogey, new BogeySample(subLevel, absSpeed, active, level.getGameTime()));
    }

    /** 物理线程读取：若该转向架处于已触发的横向脉冲期则返回方向 {@code ±1}，否则返回 {@code 0}。 */
    public static double activePulseSign(PhysicsBogeyBlockEntity bogey) {
        ActivePulse pulse = ACTIVE_PULSES.get(bogey);
        return pulse != null ? pulse.sign() : 0.0D;
    }

    /** 统计同一 Sable 结构同轴当前正在施力中的车架数量（用于微侧倾力矩方差分摊）。 */
    public static int countPushingSameAxisBogeys(UUID subLevelId, Direction.Axis axis) {
        int count = 0;
        for (ActivePulse pulse : ACTIVE_PULSES.values()) {
            if (pulse.subLevelId().equals(subLevelId) && pulse.axis() == axis) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private static void onLevelPostTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Config.isLoaded()) {
            return;
        }
        int interval = TrainLateralForceMath.intervalTicks(Config.TRAIN_SHAKE_INTERVAL_SECONDS.get());
        boolean enabled = Config.TRAIN_LATERAL_FORCE.get()
                && interval > 0
                && Config.TRAIN_SHAKE_FORCE_PN.get() > 0;
        if (!enabled) {
            clearLevel(level);
            return;
        }

        stepActivePulses(level);

        LevelShakeState state = LEVELS.get(level);
        if (state == null || state.samples.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        Map<ServerSubLevel, List<Map.Entry<PhysicsBogeyBlockEntity, BogeySample>>> bySubLevel = new HashMap<>();
        Iterator<Map.Entry<PhysicsBogeyBlockEntity, BogeySample>> it = state.samples.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PhysicsBogeyBlockEntity, BogeySample> entry = it.next();
            PhysicsBogeyBlockEntity bogey = entry.getKey();
            BogeySample sample = entry.getValue();
            if (bogey.isRemoved() || bogey.getLevel() != level || sample.gameTime() != now) {
                it.remove();
                continue;
            }
            if (!sample.active() || sample.subLevel().isRemoved()) {
                continue;
            }
            bySubLevel.computeIfAbsent(sample.subLevel(), key -> new ArrayList<>()).add(entry);
        }

        Set<UUID> seenSubLevels = new HashSet<>();
        RandomSource random = level.getRandom();
        for (Map.Entry<ServerSubLevel, List<Map.Entry<PhysicsBogeyBlockEntity, BogeySample>>> subEntry :
                bySubLevel.entrySet()) {
            ServerSubLevel subLevel = subEntry.getKey();
            UUID subId = subLevel.getUniqueId();
            seenSubLevels.add(subId);

            List<Map.Entry<PhysicsBogeyBlockEntity, BogeySample>> candidates = subEntry.getValue();
            candidates.sort(Comparator.comparingLong(e -> e.getKey().getBlockPos().asLong()));
            double[] speeds = new double[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                speeds[i] = candidates.get(i).getValue().absSpeed();
            }

            TrainLateralForceMath.SubLevelDrawState drawState = state.drawStates.computeIfAbsent(
                    subId, key -> new TrainLateralForceMath.SubLevelDrawState());
            TrainLateralForceMath.DrawOutcome outcome = TrainLateralForceMath.stepSubLevelDraw(
                    drawState,
                    interval,
                    speeds,
                    random::nextInt,
                    random::nextDouble,
                    random::nextBoolean);
            if (outcome != null) {
                PhysicsBogeyBlockEntity chosen = candidates.get(outcome.candidateIndex()).getKey();
                ACTIVE_PULSES.put(
                        chosen,
                        new ActivePulse(
                                subId,
                                chosen.getFacing().getAxis(),
                                outcome.sign(),
                                TrainLateralForceMath.PUSH_DURATION_TICKS));
                ChassisRailLock.notifyLateralShakePulse(subLevel);
            }
        }

        state.drawStates.keySet().retainAll(seenSubLevels);
    }

    private static void stepActivePulses(ServerLevel level) {
        Iterator<Map.Entry<PhysicsBogeyBlockEntity, ActivePulse>> it = ACTIVE_PULSES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PhysicsBogeyBlockEntity, ActivePulse> entry = it.next();
            PhysicsBogeyBlockEntity bogey = entry.getKey();
            if (bogey.isRemoved() || bogey.getLevel() != level) {
                if (bogey.isRemoved() || bogey.getLevel() == null || bogey.getLevel() == level) {
                    it.remove();
                }
                continue;
            }
            if (!bogey.getOptions().enabled
                    || !bogey.hasTrack()
                    || !(Sable.HELPER.getContaining(bogey) instanceof ServerSubLevel subLevel)
                    || subLevel.isRemoved()) {
                it.remove();
                continue;
            }
            ActivePulse pulse = entry.getValue();
            if (pulse.remainingTicks() <= 1) {
                it.remove();
            } else {
                entry.setValue(new ActivePulse(
                        pulse.subLevelId(),
                        pulse.axis(),
                        pulse.sign(),
                        pulse.remainingTicks() - 1));
                ChassisRailLock.notifyLateralShakePulse(subLevel);
            }
        }
    }

    private static void clearLevel(ServerLevel level) {
        LEVELS.remove(level);
        ACTIVE_PULSES.keySet().removeIf(bogey -> bogey.isRemoved() || bogey.getLevel() == null || bogey.getLevel() == level);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            clearLevel(level);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE_PULSES.clear();
        LEVELS.clear();
    }
}
