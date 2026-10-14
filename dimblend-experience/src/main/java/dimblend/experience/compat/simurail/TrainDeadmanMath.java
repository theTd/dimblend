package dimblend.experience.compat.simurail;

import java.util.List;

/**
 * E7' 无人值守刹车锁存的纯函数。
 * 速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s），坐标均为世界坐标。
 */
public final class TrainDeadmanMath {

    /** 一个登记车架的值守探针：世界坐标与 |visualSpeed|。 */
    public record Probe(double x, double y, double z, double speed) {
    }

    /**
     * 值守判定（玩家视角，2026-10-14 用户口径）：离玩家<b>最近</b>的车架距离不超过
     * {@code radius} 且该车架超速（{@link TrainSpeedGateMath#isFast}）才算在跟车。
     * 只看最近一架——旁站停放的车不能「代管」远处行驶的车；长编组司机就近值守
     * 本节即有效，不沿用 E3/E4 的同车云启发式（加减速瞬间首尾速度差会踢散云）。
     */
    public static boolean isAttending(double px, double py, double pz,
                                      List<Probe> members, double radius) {
        double radiusSq = radius * radius;
        Probe nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Probe member : members) {
            double dx = px - member.x();
            double dy = py - member.y();
            double dz = pz - member.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = member;
            }
        }
        return nearest != null && nearestSq <= radiusSq && TrainSpeedGateMath.isFast(nearest.speed());
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

    private TrainDeadmanMath() {
    }
}
