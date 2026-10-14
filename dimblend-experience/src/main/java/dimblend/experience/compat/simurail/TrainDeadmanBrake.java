package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E7' 无人值守刹车锁存（服务端，仅 rotating；v1.18 取代 E7 离结构传送）：
 * 每 10 tick 扫描一次——同维度存在 |visualSpeed| &gt; 4 m/s 的车架，且没有任何
 * 非旁观玩家在跟车时，全维度进入刹车锁存。在跟车为玩家视角
 * （{@link TrainDeadmanMath#isAttending}）：离玩家最近的车架在值守半径
 * （{@code trainDeadmanRadius}，默认 32，/dbx 面板可调）内且超速——只看最近一架，
 * 旁站停放的车不能代管远处行驶的车，长编组/多节编组（车钩挂接、各车厢未必同一
 * sable 子层级）司机就近值守本节即有效。锁存期间 {@code getBrakeStrength()} 经
 * mixin 强制满刹，直到任意车架红石信号<b>通一次再断开</b>（锁存期间见过 0→正 沿，
 * 再出现 正→0 沿）才解除。锁存不自动解除、不随停车解除。
 *
 * <p>玩家坐标经 {@link SableWorldPosition#project} 投影到世界坐标，车上乘客落在车体
 * 位置，天然在半径内。锁存为内存态，重启即丢（接受项）。锁存变化经
 * {@link DeadmanLatchPayload} 广播给维度内玩家，驱动客户端锁存粒子。</p>
 *
 * <p>本类不由事件总线加载：唯一入口是 {@code PhysicsBogeyDeadmanBrakeMixin} 的服务端
 * tick 挂钩（simurail 在场才应用，其硬性依赖 sable），因此可硬引用第三方类型——
 * 与 {@code TrainBrakeBroadcast} 同型，区别于 @EventBusSubscriber 的惰性解析约定。</p>
 */
public final class TrainDeadmanBrake {

    /** 值守扫描间隔（tick）：0.5 秒。 */
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final double SIGNAL_EPSILON = 1.0E-6;

    /** 已锁存的维度。物理线程读（getBrakeStrength mixin）、服务端线程写，故用并发集合。 */
    private static final Set<ResourceKey<Level>> LATCHED = ConcurrentHashMap.newKeySet();

    /**
     * 每架上一 tick 的信号状态、锁存期间「已见通沿」标记、各维度上次扫描 tick。
     * 仅服务端 tick 线程触达（与 {@code TrainBrakeBroadcast} 登记同线程），
     * 弱引用键随车架卸载脱落。
     */
    private static final Map<PhysicsBogeyBlockEntity, Boolean> LAST_SIGNAL_HIGH = new WeakHashMap<>();
    private static final Map<PhysicsBogeyBlockEntity, Boolean> PULSE_ARMED = new WeakHashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_SCAN_TICKS = new HashMap<>();

    /** 转向架服务端 tick 入口（mixin 已确认非客户端）：信号沿跟踪 + 节流值守扫描。 */
    public static void onBogeyServerTick(PhysicsBogeyBlockEntity bogey) {
        if (!Config.TRAIN_DEADMAN_BRAKE.get()) {
            releaseAll(bogey.getLevel().getServer());
            return;
        }
        Level level = bogey.getLevel();
        trackSignalEdge(bogey, level);
        scanThrottled(level);
    }

    /** 供刹车 mixin 查询：该维度是否处于强制满刹锁存（config 关闭视为未锁存）。 */
    public static boolean isLatched(ResourceKey<Level> dim) {
        return Config.TRAIN_DEADMAN_BRAKE.get() && LATCHED.contains(dim);
    }

    /**
     * 解除条件：锁存期间见过通沿（0→正，arm），再出现断沿（正→0）→ 解除该维度锁存。
     * 锁存前已常通的信号没有锁存期内的通沿，仅断开不构成完整脉冲，不解除
     * （未锁存维度每 tick 清零 arm，{@link TrainDeadmanMath#stepPulse} 保证该不变式）。
     * 信号读 E9 压顶后的控制强度：上限 ≥1 故正信号恒为正、无信号恒为 0。
     */
    private static void trackSignalEdge(PhysicsBogeyBlockEntity bogey, Level level) {
        boolean high = bogey.getControlStrength() > SIGNAL_EPSILON;
        boolean prevHigh = Boolean.TRUE.equals(LAST_SIGNAL_HIGH.put(bogey, high));
        boolean armed = PULSE_ARMED.containsKey(bogey);
        TrainDeadmanMath.PulseStep step =
                TrainDeadmanMath.stepPulse(prevHigh, high, armed, LATCHED.contains(level.dimension()));
        if (step.armed()) {
            PULSE_ARMED.put(bogey, Boolean.TRUE);
        } else {
            PULSE_ARMED.remove(bogey);
        }
        if (step.release()) {
            release(level, level.dimension());
        }
    }

    /** 每维度每 {@link #CHECK_INTERVAL_TICKS} tick 扫一次；已锁存或非 rotating 维度不扫。 */
    private static void scanThrottled(Level level) {
        ResourceKey<Level> dim = level.dimension();
        long now = level.getGameTime();
        Long last = LAST_SCAN_TICKS.get(dim);
        if (last != null && now - last < CHECK_INTERVAL_TICKS) {
            return;
        }
        LAST_SCAN_TICKS.put(dim, now);
        if (!RotatingDimension.is(level) || LATCHED.contains(dim)) {
            return;
        }
        scanAndLatch(level, dim, now);
    }

    private static void scanAndLatch(Level level, ResourceKey<Level> dim, long now) {
        List<TrainBrakeBroadcast.Member> members = TrainBrakeBroadcast.snapshotFresh(dim, now);
        if (members.isEmpty()) {
            return;
        }
        List<TrainDeadmanMath.Probe> probes = new ArrayList<>(members.size());
        boolean anyFast = false;
        for (TrainBrakeBroadcast.Member member : members) {
            Vec3 pos = member.worldPos();
            probes.add(new TrainDeadmanMath.Probe(pos.x, pos.y, pos.z, member.speed()));
            anyFast |= TrainSpeedGateMath.isFast(member.speed());
        }
        if (!anyFast) {
            return;
        }
        double radius = Config.TRAIN_DEADMAN_RADIUS.get();
        // 值守为玩家视角：离玩家最近的车架在半径内且超速才算在跟车
        for (Player player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            Vec3 projected = SableWorldPosition.project(level, player.position());
            if (TrainDeadmanMath.isAttending(projected.x, projected.y, projected.z, probes, radius)) {
                return;
            }
        }
        if (LATCHED.add(dim)) {
            broadcast(level.getServer(), dim, true);
        }
    }

    /** 解除单个维度的锁存并向其玩家广播（车架信号脉冲路径）。 */
    private static void release(Level level, ResourceKey<Level> dim) {
        if (LATCHED.remove(dim)) {
            broadcast(level.getServer(), dim, false);
        }
    }

    /** config 关闭路径：解除全部锁存并逐维度广播。 */
    private static void releaseAll(MinecraftServer server) {
        if (LATCHED.isEmpty()) {
            return;
        }
        for (ResourceKey<Level> dim : List.copyOf(LATCHED)) {
            if (LATCHED.remove(dim)) {
                broadcast(server, dim, false);
            }
        }
    }

    private static void broadcast(MinecraftServer server, ResourceKey<Level> dim, boolean latched) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(dim);
        if (level != null) {
            DeadmanLatchPayload.sendToDimension(level, latched);
        }
    }

    private TrainDeadmanBrake() {
    }
}
