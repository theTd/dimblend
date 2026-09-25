package dimblend.experience.client;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * E2 整车单循环仲裁（纯客户端）。
 *
 * <p>背景：行进声按转向架粒度每架各播会叠成混乱声。收敛为<b>整车只由 leader 起脉冲</b>：
 * 同车成员中按 {@code identityHashCode} 最小者当选 leader，只有 leader 按集群最大速度
 * 驱动包络，并以固定 1 秒间隔起单次音（间隔不随音调拉伸）。</p>
 *
 * <p>同车判定（F-1：纯距离聚类会把车场内 60m 外的另一列动车并入同一集群，
 * 致非 leader 列车在玩家身边静默，属回归）：以 sable 子层级身份为主
 * （spec E0：列车以 sub-level 为单位整体运作），不同子层级但世界距离 ≤
 * {@link #COUPLE_RADIUS} 的成员按车钩邻接处理，连锁 transitive 闭包即整列；
 * 60m 开外的他车链不上，自然分离。未组装（subId 为空）退化为纯距离邻接。</p>
 *
 * <p>只碰客户端：调用方（行进声 mixin）须先确认是客户端；弱引用注册表，被移除/卸载的
 * 车架靠时间戳过期脱落（{@link #STALE_TICKS}）。交接（leader 卸载）时新 leader 另起
 * 一条，旧实例靠 {@link BogeyTrackSound} 的孤儿看门狗淡出自停。维度身份必带，
 * 切维度后旧条目不参选（F-4）。</p>
 */
public final class TrainLoopLeader {

    /** 车钩邻接半径（米）：同列相邻车架/车厢的链接上限，远小于站场股道间距的误并量级。 */
    public static final double COUPLE_RADIUS = 25.0;
    private static final double COUPLE_RADIUS_SQ = COUPLE_RADIUS * COUPLE_RADIUS;
    /** 成员活跃窗（tick）：超窗未登记视为已卸载，不参选、不计速（与孤儿看门狗 30t 对齐，只小不大）。 */
    static final long STALE_TICKS = 20L;

    private record Member(Vec3 worldPos, double speed, long tick, ResourceKey<Level> dim, UUID subId) {
    }

    private record Node(PhysicsBogeyBlockEntity bogey, Vec3 worldPos, double speed, UUID subId) {
    }

    private static final Map<PhysicsBogeyBlockEntity, Member> MEMBERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private TrainLoopLeader() {
    }

    /** 每客户端 tick 登记一次。dim/subId 均由调用方传入，本类不碰 sable（保持可单元测试）。 */
    public static void updateMember(PhysicsBogeyBlockEntity bogey, Vec3 worldPos, double speed,
                                    ResourceKey<Level> dim, UUID subId, long nowTick) {
        if (bogey.isRemoved()) {
            MEMBERS.remove(bogey);
            return;
        }
        MEMBERS.put(bogey, new Member(worldPos, speed, nowTick, dim, subId));
        if ((nowTick & 63L) == 0L) {
            prune(nowTick);
        }
    }

    public static void forget(PhysicsBogeyBlockEntity bogey) {
        MEMBERS.remove(bogey);
    }

    /**
     * 本车架是否为其集群的 leader。自身不可闻直接返回 false（静置邻车不抢播）。
     * 候选 = 同维度 + 新鲜 + 连锁可达 + 可闻；按 identityHashCode 最小确定，保证集群内唯一。
     */
    public static boolean isLeader(PhysicsBogeyBlockEntity self, Vec3 selfPos, double selfSpeed,
                                   ResourceKey<Level> selfDim, UUID selfSubId, long nowTick) {
        if (selfSpeed < BogeyTrackSound.MIN_AUDIBLE_SPEED) {
            return false;
        }
        List<Node> cluster = reachable(selfPos, selfSubId, snapshot(selfDim, nowTick));
        PhysicsBogeyBlockEntity leader = self;
        int minHash = System.identityHashCode(self);
        for (Node node : cluster) {
            if (node.speed < BogeyTrackSound.MIN_AUDIBLE_SPEED) {
                continue;
            }
            int hash = System.identityHashCode(node.bogey);
            if (hash < minHash) {
                minHash = hash;
                leader = node.bogey;
            }
        }
        return leader == self;
    }

    /** 集群速度：连锁可达成员的最大 |visualSpeed|，leader 按此驱动音调。 */
    public static double clusterSpeed(Vec3 selfPos, UUID selfSubId, ResourceKey<Level> selfDim, long nowTick) {
        double max = 0.0;
        for (Node node : reachable(selfPos, selfSubId, snapshot(selfDim, nowTick))) {
            max = Math.max(max, node.speed);
        }
        return max;
    }

    /** 快照：同维度新鲜未移除成员（锁内复制，锁外 BFS）。 */
    private static List<Node> snapshot(ResourceKey<Level> dim, long nowTick) {
        List<Node> out = new ArrayList<>();
        synchronized (MEMBERS) {
            for (Map.Entry<PhysicsBogeyBlockEntity, Member> entry : MEMBERS.entrySet()) {
                PhysicsBogeyBlockEntity bogey = entry.getKey();
                if (bogey.isRemoved()) {
                    continue;
                }
                Member member = entry.getValue();
                if (nowTick - member.tick > STALE_TICKS || !member.dim.equals(dim)) {
                    continue;
                }
                out.add(new Node(bogey, member.worldPos, member.speed, member.subId));
            }
        }
        return out;
    }

    /**
     * 连锁可达集：从起点经"同子层级 / 距离≤车钩半径"边能走到的快照成员。
     * 起点自身若已登记（距自身距离为 0 恒链接）同样在集中——选举/计速均为
     * 最小值/最大值语义，自含无害，调用方无需另行计入自身。
     */
    private static List<Node> reachable(Vec3 anchorPos, UUID anchorSubId,
                                        List<Node> snapshot) {
        List<Node> out = new ArrayList<>();
        Set<PhysicsBogeyBlockEntity> visited = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        for (Node node : snapshot) {
            if (linked(anchorPos, anchorSubId, node.worldPos, node.subId)) {
                if (visited.add(node.bogey)) {
                    queue.add(node);
                }
            }
        }
        while (!queue.isEmpty()) {
            Node current = queue.removeFirst();
            out.add(current);
            for (Node node : snapshot) {
                if (!visited.contains(node.bogey)
                        && linked(current.worldPos, current.subId, node.worldPos, node.subId)) {
                    visited.add(node.bogey);
                    queue.add(node);
                }
            }
        }
        return out;
    }

    /** 链接边：同非空子层级，或世界距离在车钩半径内。 */
    private static boolean linked(Vec3 aPos, UUID aSub, Vec3 bPos, UUID bSub) {
        if (aSub != null && aSub.equals(bSub)) {
            return true;
        }
        return aPos.distanceToSqr(bPos) <= COUPLE_RADIUS_SQ;
    }

    private static void prune(long nowTick) {
        synchronized (MEMBERS) {
            MEMBERS.entrySet().removeIf(entry ->
                    entry.getKey().isRemoved() || nowTick - entry.getValue().tick > STALE_TICKS * 4L);
        }
    }
}
