package dimblend.experience.compat.cdg;

/**
 * B9 柴油机 boost 启动（2026-10-15 窗子拍板定稿；纯函数，零依赖）：
 * 柴油机运转且未达额定（"启动直到达到额定转速"期间），每次点火会话仅一次——
 * 若 |网络转速| > 当前阶梯转速 rampTarget，把 rampTicks 前推使
 * rampTarget = min(|网络转速|, 0.8 × 额定)，之后阶梯照常每 80gt +2rpm 爬完剩余部分。
 * 设计动机：借网络转速实现 boost 启动，封顶 80% 留 20% 自爬（窗子拍板）。
 *
 * <p>阶梯公式（普通/组合式与巨型两 mixin 共用）：stepped = min(16 + 2×(rampTicks/80), rated)；
 * 逆推 rampTicks = floor((target − 16) × 40)。</p>
 */
public final class CdgBoostMath {

    /** 起步转速（与两 mixin 阶梯公式同源：16 + 2×(rampTicks/80)）。 */
    public static final float IGNITION_RPM = 16.0F;
    /** 对齐封顶系数：0.8 × 额定（封顶恒小于额定，B3 波动/饱和判定不受影响）。 */
    public static final float CAP_FACTOR = 0.8F;

    /** 对齐目标：min(|网络转速|, 0.8 × 额定)。 */
    public static float boostTarget(float networkSpeedAbs, float rated) {
        return Math.min(networkSpeedAbs, CAP_FACTOR * rated);
    }

    /**
     * 是否触发：本次会话未消费且对齐目标 > 当前阶梯且目标 > 起步 16——
     * 网络 ≤ 自身（目标不超前当前阶梯）/ 对齐目标 ≤ 当前 rampTarget / 目标 ≤ 起步 16 都不触发。
     */
    public static boolean shouldBoost(boolean consumed, float target, float rampTarget) {
        return !consumed && target > rampTarget && target > IGNITION_RPM;
    }

    /**
     * 逆推 rampTicks = floor((target − 16) × 40)：使阶梯取值不超过 target 的最大档位
     * （整数除法向下取精，不 overshoot）。
     */
    public static int rampTicksFor(float target) {
        return (int) Math.floor((target - IGNITION_RPM) * 40.0F);
    }

    private CdgBoostMath() {
    }
}
