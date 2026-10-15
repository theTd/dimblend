package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.exploration.RotatingDimension;
import dimblend.experience.mixin.compat.simurail.AutomaticCouplerPartnerAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E7': emergency brake for unattended moving consists in rotating.
 * Bogey ticks only collect samples. The level's post-tick scans a complete fleet, using actual
 * carriage/coupler topology and freshly projected positions rather than a sound-broadcast cache.
 * Latches are scoped to connected consists; any raw redstone level change resets that whole consist.
 */
public final class TrainDeadmanBrake {
    private static final int CHECK_INTERVAL_TICKS = 10;

    // Only the latch is read by the physics thread. All other state belongs to the server thread.
    private static final Map<ResourceKey<Level>, Set<UUID>> LATCHED = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Fleet> FLEETS = new IdentityHashMap<>();
    /**
     * 锁存强制过载的 per-bogey 强制 impact 表（服务端权威；MRV 钩子只读本表、
     * 不在钩子内反算——calculateStressApplied 会被网络更新重入调用，读网络容量
     * （calculateCapacity 会顺手剔除失效 source，javap 核实）不干净，故反算收敛在
     * 锁存变化/扫描时的服务端主线程，见 spec E7'）。ConcurrentHashMap：网络账本
     * 重缓存路径与主线程并发读安全；键随 purge 路径（解除/卸载/失效）清。
     */
    private static final Map<PhysicsBogeyBlockEntity, Float> FORCED_IMPACTS = new ConcurrentHashMap<>();

    private record Sample(double speed, int signal, long tick) {
    }

    private static final class Fleet {
        final Map<PhysicsBogeyBlockEntity, Sample> samples = new WeakHashMap<>();
        final Map<PhysicsBogeyBlockEntity, Integer> lastSignal = new WeakHashMap<>();
    }

    /** Called only when Simurail (and its required Sable dependency) is installed. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(TrainDeadmanBrake::onLevelTick);
        NeoForge.EVENT_BUS.addListener(TrainDeadmanBrake::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(TrainDeadmanBrake::onServerStopped);
    }

    public static void onBogeyServerTick(PhysicsBogeyBlockEntity bogey, double speed, int signal) {
        if (!(bogey.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)
                || !Config.TRAIN_DEADMAN_BRAKE.get()) {
            return;
        }
        FLEETS.computeIfAbsent(level, key -> new Fleet()).samples.put(bogey,
                new Sample(speed, signal, level.getGameTime()));
    }

    public static boolean isLatched(PhysicsBogeyBlockEntity bogey) {
        if (!Config.TRAIN_DEADMAN_BRAKE.get()) {
            return false;
        }
        SubLevel carriage = Sable.HELPER.getContaining(bogey);
        return carriage != null && latchedCarriages(bogey.getLevel().dimension()).contains(carriage.getUniqueId());
    }

    /**
     * 锁存强制过载（E7'）：MRV 钩子查询本转向架的强制 impact；未锁存/客户端返回
     * {@code null} 透传原值。只读表、无副作用无分配。
     */
    public static Float forcedImpactOf(PhysicsBogeyBlockEntity bogey) {
        return FORCED_IMPACTS.get(bogey);
    }

    /** Immutable snapshot shared with the physics reader and the login/dimension sync path. */
    public static Set<UUID> latchedCarriages(ResourceKey<Level> dim) {
        return LATCHED.getOrDefault(dim, Set.of());
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Config.isLoaded()) {
            return;
        }
        if (!Config.TRAIN_DEADMAN_BRAKE.get()) {
            if (LATCHED.remove(level.dimension()) != null) {
                DeadmanLatchPayload.sendToDimension(level, Set.of());
            }
            // 关开关=全部解除：恢复真实 impact 并清表（空锁存集=只走恢复侧）
            syncForcedImpacts(level, FLEETS.remove(level), Set.of());
            return;
        }
        Fleet fleet = FLEETS.get(level);
        if (fleet == null) {
            return;
        }
        long now = level.getGameTime();
        // One tick of tolerance covers Sable actor-tick ordering; never reuse the old 40-tick cache.
        fleet.samples.entrySet().removeIf(entry -> entry.getKey().isRemoved()
                || entry.getKey().getLevel() != level
                || now < entry.getValue().tick() || now - entry.getValue().tick() > 1);
        fleet.lastSignal.keySet().retainAll(fleet.samples.keySet());
        Set<UUID> signalChanges = new HashSet<>();
        for (var entry : fleet.samples.entrySet()) {
            var bogey = entry.getKey();
            int signal = entry.getValue().signal();
            Integer previous = fleet.lastSignal.put(bogey, signal);
            if (TrainDeadmanMath.signalChanged(previous, signal)) {
                SubLevel carriage = Sable.HELPER.getContaining(bogey);
                if (carriage != null && !carriage.isRemoved()) {
                    signalChanges.add(carriage.getUniqueId());
                }
            }
        }
        boolean scan = now % CHECK_INTERVAL_TICKS == 0;
        if (!scan && signalChanges.isEmpty()) {
            return;
        }
        List<Vec3> attendants = new ArrayList<>();
        if (scan) {
            for (var player : level.players()) {
                if (!player.isSpectator()) {
                    attendants.add(SableWorldPosition.project(level, player.position()));
                }
            }
        }
        double radius = Config.TRAIN_DEADMAN_RADIUS.get();
        var consists = TrainDeadmanMath.cluster(snapshotCarriages(level, fleet));
        Set<UUID> previous = latchedCarriages(level.dimension());
        Set<UUID> next = TrainDeadmanMath.updateLatches(previous, consists, signalChanges,
                consist -> scan && isUnattended(consist.bogeys(), attendants, radius * radius));
        if (!next.equals(previous)) {
            if (next.isEmpty()) {
                LATCHED.remove(level.dimension());
            } else {
                LATCHED.put(level.dimension(), next);
            }
            DeadmanLatchPayload.sendToDimension(level, next);
        }
        // 锁存强制过载对齐：锁存变化（立即生效/恢复）之外，扫描周期内重进chunk的
        // 转向架（新 BE 不在表内）也在此补算——仅缺表时推 updateStressFor，不逐 tick 推
        syncForcedImpacts(level, fleet, next);
    }

    /**
     * 锁存强制过载表与 Create 网络账本对齐（服务端主线程，锁存变化或扫描周期调用）：
     * 解除/失效侧对仍在线的转向架 {@code updateStressFor} 恢复真实 impact 并清表；
     * 施加侧为锁存车厢中缺表的转向架反算强制 impact 并立即推账——锁存/解除瞬间
     * Create 不会自动重算 {@code calculateStressApplied}（需求缓存 impact 只在
     * add/updateStressFor 时写），必须显式推一次；禁止逐 tick 推（updateStress 触发
     * sync 抖动），仅缺表/恢复时推。
     */
    private static void syncForcedImpacts(ServerLevel level, Fleet fleet, Set<UUID> latched) {
        var iterator = FORCED_IMPACTS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PhysicsBogeyBlockEntity bogey = entry.getKey();
            if (bogey.getLevel() != level) {
                continue;
            }
            SubLevel carriage = Sable.HELPER.getContaining(bogey);
            boolean stillLatched = !bogey.isRemoved() && carriage != null && !carriage.isRemoved()
                    && latched.contains(carriage.getUniqueId());
            if (!stillLatched) {
                iterator.remove();
                if (!bogey.isRemoved() && bogey.hasNetwork()) {
                    // calculateStressApplied 此刻表项已清、读回真实 impact（恢复口径）
                    bogey.getOrCreateNetwork().updateStressFor(bogey, bogey.calculateStressApplied());
                }
            }
        }
        if (fleet == null) {
            return;
        }
        for (PhysicsBogeyBlockEntity bogey : fleet.samples.keySet()) {
            if (FORCED_IMPACTS.containsKey(bogey)) {
                continue;
            }
            SubLevel carriage = Sable.HELPER.getContaining(bogey);
            if (carriage == null || carriage.isRemoved() || !latched.contains(carriage.getUniqueId())) {
                continue;
            }
            float forced = computeForcedImpact(bogey);
            FORCED_IMPACTS.put(bogey, forced);
            if (bogey.hasNetwork()) {
                bogey.getOrCreateNetwork().updateStressFor(bogey, forced);
            }
        }
    }

    /**
     * 强制 impact 反算：有网络按当前总容量反算（需求 = impact × |理论转速| >
     * 容量 × 1.5 + 1，跨转速恒过载；容量随拓扑变化后强制值按进入时快照可能
     * 不再过量——接受项见 spec E7'）；锁存瞬间无网络回退
     * {@link TrainDeadmanStressMath#FALLBACK_IMPACT}（窗子原数 65536）。
     */
    private static float computeForcedImpact(PhysicsBogeyBlockEntity bogey) {
        if (!bogey.hasNetwork()) {
            return TrainDeadmanStressMath.FALLBACK_IMPACT;
        }
        KineticNetwork network = bogey.getOrCreateNetwork();
        if (network == null) {
            return TrainDeadmanStressMath.FALLBACK_IMPACT;
        }
        // calculateCapacity 迭代 sources 会顺手剔除失效 source（javap 核实）——服务端
        // 主线程此处调用与 Create 自身 updateNetwork 同语境，可接受；故不放进 MRV 钩子
        return TrainDeadmanStressMath.forcedImpact(network.calculateCapacity(), bogey.getTheoreticalSpeed());
    }

    private static boolean isUnattended(List<TrainDeadmanMath.Probe> bogeys, List<Vec3> attendants,
                                        double radiusSq) {
        if (!TrainDeadmanMath.hasFast(bogeys)) {
            return false;
        }
        for (Vec3 player : attendants) {
            if (TrainDeadmanMath.nearestDistanceSq(bogeys, player.x, player.y, player.z) <= radiusSq) {
                return false;
            }
        }
        return true;
    }

    private static List<TrainDeadmanMath.Carriage> snapshotCarriages(ServerLevel level, Fleet fleet) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return List.of();
        }
        Map<UUID, List<TrainDeadmanMath.Probe>> probes = new HashMap<>();
        for (var entry : fleet.samples.entrySet()) {
            var bogey = entry.getKey();
            SubLevel carriage = Sable.HELPER.getContaining(bogey);
            if (carriage == null || carriage.isRemoved()) {
                continue; // Unassembled blocks are not a moving train.
            }
            Vec3 pos = SableWorldPosition.project(level, bogey.getBlockPos().getCenter());
            probes.computeIfAbsent(carriage.getUniqueId(), key -> new ArrayList<>())
                    .add(new TrainDeadmanMath.Probe(pos.x, pos.y, pos.z, entry.getValue().speed()));
        }
        List<TrainDeadmanMath.Carriage> carriages = new ArrayList<>();
        for (SubLevel carriage : container.getAllSubLevels()) {
            if (carriage.isRemoved()) {
                continue;
            }
            List<UUID> partners = new ArrayList<>();
            for (var actor : carriage.getPlot().getBlockEntityActors()) {
                if (actor instanceof AutomaticCouplerBlockEntity coupler && !coupler.isRemoved()) {
                    // The upstream dependency helper wraps getSubLevel(partner) in List.of,
                    // which throws for an unloaded partner. Read the same ID and let cluster
                    // ignore absent nodes instead; do not force-load chunks or invent links.
                    UUID partner = ((AutomaticCouplerPartnerAccessor) coupler).dimblend$getPartnerSubLevelId();
                    if (partner != null) {
                        partners.add(partner);
                    }
                }
            }
            carriages.add(new TrainDeadmanMath.Carriage(carriage.getUniqueId(), partners,
                    probes.getOrDefault(carriage.getUniqueId(), List.of())));
        }
        return carriages;
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FLEETS.remove(level);
            LATCHED.remove(level.dimension());
            FORCED_IMPACTS.entrySet().removeIf(entry -> entry.getKey().getLevel() == level);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        FLEETS.clear();
        LATCHED.clear();
        FORCED_IMPACTS.clear();
    }

    private TrainDeadmanBrake() {
    }
}
