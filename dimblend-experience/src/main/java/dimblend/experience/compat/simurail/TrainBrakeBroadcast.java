package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 服务端刹车整车广播（E3/E4）。
 *
 * <p>背景：运行 jar 的刹车强度是单车架粒度（{@code getBrakeStrength()} 源自本车架红石信号，
 * 无编组级传播），沿检测只能看到受电车架自身的变化。实测 5 节×6 架编组对其中一架施闸时，
 * 只有该架播音——用户口径要求沿触发时<b>整列车架都播</b>（司机 brake 手柄是整车语义），
 * 故沿触发后向同列其余车架位置补播。</p>
 *
 * <p>同列判定（无拓扑遍历，sable 未暴露 UUID→子层级查询）：注册表内同维度、登记新鲜、
 * 与沿位置距离 ≤ {@link #BROADCAST_RADIUS} 且 |visualSpeed| 差 ≤ 1.5m/s 的成员。
 * 速度窗用于排除车场内并排停放的他车；静置整车施闸时全员速度均为 0，天然同窗。
 * 同速并排邻车误播为已知接受项（见 spec）。</p>
 *
 * <p>沿去重（F-2）：整车布线时多架同 tick 沿触发，每架各播一次全列即 N×M 叠加。
 * 同类型事件在 {@link #DEDUP_WINDOW_TICKS} 内、沿位置同半径内直接跳过——整列只播一次。</p>
 *
 * <p>坐标写法（F-6）：服务端用 {@code projectOutOfSubLevel}（车钩同型先例），
 * 客户端行进声用子层级 {@code logicalPose().transformPosition}（粒子先例）；
 * 两者都是局部→世界坐标换算，结果等价，各守各端惯例。</p>
 *
 * <p>只碰服务端：调用方（刹车 mixin）须先确认非客户端；弱引用注册表 + 时间戳过期，
 * 被移除/卸载的车架自动脱落。沿事件稀少，广播时快照+按距离排序+截断，
 * 常规 tick 只有一次 O(1) 登记。</p>
 */
public final class TrainBrakeBroadcast {

    /** 同车判定半径（米）：覆盖 5 节编组整列；超长编组按就近截断（另有 {@code BROADCAST_CAP}）。 */
    public static final double BROADCAST_RADIUS = 120.0;
    private static final double BROADCAST_RADIUS_SQ = BROADCAST_RADIUS * BROADCAST_RADIUS;
    /** 同车速度一致窗（m/s）。 */
    private static final double SPEED_WINDOW = 1.5;
    /** 单次沿最多播放数：防超长编组/车场密集区包风暴。 */
    private static final int BROADCAST_CAP = 48;
    /** 成员活跃窗（tick）：超窗未登记视为已卸载，不补播。 */
    private static final long STALE_TICKS = 40L;
    /** 同类型沿去重窗（tick）。 */
    private static final long DEDUP_WINDOW_TICKS = 5L;

    private record Member(Vec3 worldPos, double speed, long tick, ResourceKey<Level> dim) {
    }

    private static final Map<PhysicsBogeyBlockEntity, Member> MEMBERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static SoundEvent lastEvent;
    private static long lastTick = -100L;
    private static Vec3 lastPos;

    private TrainBrakeBroadcast() {
    }

    /** 每服务端 tick 登记一次世界坐标、|visualSpeed| 与当前游戏 tick（调用方须已确认非客户端）。 */
    public static void updateMember(PhysicsBogeyBlockEntity bogey, double speed, long nowTick) {
        if (bogey.isRemoved()) {
            MEMBERS.remove(bogey);
            return;
        }
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(bogey.getLevel(), bogey.getBlockPos().getCenter());
        MEMBERS.put(bogey, new Member(world, speed, nowTick, bogey.getLevel().dimension()));
        if ((nowTick & 63L) == 0L) {
            prune(nowTick);
        }
    }

    public static void forget(PhysicsBogeyBlockEntity bogey) {
        MEMBERS.remove(bogey);
    }

    /**
     * 同维度是否有登记新鲜、|visualSpeed| 大于 {@code minSpeed} 的车架。
     * 供 E7 离结构传送做全局速度闸；过期成员不计。
     */
    public static boolean hasAnyFasterThan(ResourceKey<Level> dim, double minSpeed, long nowTick) {
        synchronized (MEMBERS) {
            for (Member member : MEMBERS.values()) {
                if (nowTick - member.tick > STALE_TICKS || !member.dim.equals(dim)) {
                    continue;
                }
                if (member.speed > minSpeed) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 沿触发整车广播：在注册表快照内按距离由近及远逐个播音（含触发者自身）。
     *
     * @return 实际播放数（去重跳过时为 0）。
     */
    public static int broadcast(Level level, Vec3 edgeWorldPos, double edgeSpeed,
                                SoundEvent event, float volume, float pitch, long nowTick) {
        synchronized (TrainBrakeBroadcast.class) {
            if (event.equals(lastEvent) && lastPos != null
                    && nowTick - lastTick <= DEDUP_WINDOW_TICKS
                    && lastPos.distanceToSqr(edgeWorldPos) <= BROADCAST_RADIUS_SQ) {
                return 0;
            }
            lastEvent = event;
            lastTick = nowTick;
            lastPos = edgeWorldPos;
        }
        ResourceKey<Level> dim = level.dimension();
        List<Member> targets = new ArrayList<>();
        synchronized (MEMBERS) {
            for (Map.Entry<PhysicsBogeyBlockEntity, Member> entry : MEMBERS.entrySet()) {
                if (entry.getKey().isRemoved()) {
                    continue;
                }
                Member member = entry.getValue();
                if (nowTick - member.tick > STALE_TICKS || !member.dim.equals(dim)) {
                    continue;
                }
                if (member.worldPos.distanceToSqr(edgeWorldPos) > BROADCAST_RADIUS_SQ) {
                    continue;
                }
                if (Math.abs(member.speed - edgeSpeed) > SPEED_WINDOW) {
                    continue;
                }
                targets.add(member);
            }
        }
        targets.sort((a, b) -> Double.compare(
                a.worldPos.distanceToSqr(edgeWorldPos), b.worldPos.distanceToSqr(edgeWorldPos)));
        int plays = 0;
        for (Member target : targets) {
            if (plays >= BROADCAST_CAP) {
                break;
            }
            level.playSound(null, target.worldPos.x, target.worldPos.y, target.worldPos.z,
                    event, SoundSource.BLOCKS, volume, pitch);
            plays++;
        }
        return plays;
    }

    private static void prune(long nowTick) {
        synchronized (MEMBERS) {
            MEMBERS.entrySet().removeIf(entry ->
                    entry.getKey().isRemoved() || nowTick - entry.getValue().tick > STALE_TICKS * 4L);
        }
    }
}
