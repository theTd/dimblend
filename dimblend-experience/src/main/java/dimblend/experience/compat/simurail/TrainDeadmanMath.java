package dimblend.experience.compat.simurail;

import java.util.ArrayList;
import java.util.List;

/**
 * E7' 无人值守刹车锁存的纯函数。
 * 速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s），坐标均为世界坐标。
 */
public final class TrainDeadmanMath {

    /**
     * 编组链距（格）：车架中心距离不超过此值视为车钩挂接的同一编组（传递闭包）。
     * 编组靠车钩挂接、各车厢未必同一 sable 子层级，拓扑不可查，只能几何聚类。
     * 双向取舍（2026-10-15 拍板 24 格，待实测编组架间距校准）：聚大——并排停放的他车
     * 误并入只会放宽跟车判定（漏刹方向）；聚小——架间距超过链距的编组被断链拆成多组
     * 逐组问责，判定变严（误刹方向，用户实测误刹 bug 的理论复发路径），由解除脉冲兜底。
     * 判定口径：对整列编组量距离，编组上离玩家<b>最近</b>的车架超过半径才算无人值守，
     * 不看该架速度。
     */
    public static final double COUPLING_CHAIN = 24.0D;
    private static final double COUPLING_CHAIN_SQ = COUPLING_CHAIN * COUPLING_CHAIN;

    /** 一个登记车架的值守探针：世界坐标与 |visualSpeed|。 */
    public record Probe(double x, double y, double z, double speed) {
    }

    /** 把探针按编组链距聚成组（传递闭包），返回组列表（组内为探针下标）。 */
    public static List<List<Integer>> cluster(List<Probe> probes) {
        int n = probes.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            Probe a = probes.get(i);
            for (int j = i + 1; j < n; j++) {
                Probe b = probes.get(j);
                double dx = a.x() - b.x();
                double dy = a.y() - b.y();
                double dz = a.z() - b.z();
                if (dx * dx + dy * dy + dz * dz <= COUPLING_CHAIN_SQ) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        List<List<Integer>> groups = new ArrayList<>();
        int[] indexByRoot = new int[n];
        java.util.Arrays.fill(indexByRoot, -1);
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            if (indexByRoot[root] < 0) {
                indexByRoot[root] = groups.size();
                groups.add(new ArrayList<>());
            }
            groups.get(indexByRoot[root]).add(i);
        }
        return groups;
    }

    /** 编组内是否存在超速车架（{@link TrainSpeedGateMath#isFast}）。 */
    public static boolean hasFast(List<Probe> probes, List<Integer> group) {
        for (int index : group) {
            if (TrainSpeedGateMath.isFast(probes.get(index).speed())) {
                return true;
            }
        }
        return false;
    }

    /** 编组上离给定点最近的车架的距离平方（距离判定不看该架速度——2026-10-15 用户口径）。 */
    public static double nearestDistanceSq(List<Probe> probes, List<Integer> group,
                                           double px, double py, double pz) {
        double nearestSq = Double.MAX_VALUE;
        for (int index : group) {
            Probe probe = probes.get(index);
            double dx = px - probe.x();
            double dy = py - probe.y();
            double dz = pz - probe.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < nearestSq) {
                nearestSq = distSq;
            }
        }
        return nearestSq;
    }

    /** 通断脉冲状态机一步的结果：新的 arm 状态与是否触发解除。 */
    public record PulseStep(boolean armed, boolean release) {
    }

    /**
     * 解除脉冲状态机：仅在锁存期间 arm 有效（未锁存一律清零——因此锁存置位瞬间
     * 该维度不可能携带残留 arm，锁存前已常通的信号仅断开不构成完整脉冲）；
     * 锁存期间见通沿（非高→高）arm，见断沿（高→非高）且已 arm 则解除并复位。
     */
    public static PulseStep stepPulse(boolean prevHigh, boolean curHigh, boolean armed, boolean latched) {
        if (!latched) {
            return new PulseStep(false, false);
        }
        if (curHigh && !prevHigh) {
            return new PulseStep(true, false);
        }
        if (!curHigh && prevHigh && armed) {
            return new PulseStep(false, true);
        }
        return new PulseStep(armed, false);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private TrainDeadmanMath() {
    }
}
