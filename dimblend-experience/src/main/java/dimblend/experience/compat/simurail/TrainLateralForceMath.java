package dimblend.experience.compat.simurail;

/**
 * E8 车架随机横向力的纯函数。
 * 速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s）；力单位为 sable 的 pN
 * （与 {@code BlockEntityPropeller#getThrust} 同单位，施加时按物理子步乘 timeStep 成冲量）。
 * 速度门槛复用 {@link TrainOffStructureMath#isFast}（&gt;4 m/s，不含等于）。
 */
public final class TrainLateralForceMath {

    /** 横向力大小（pN）。 */
    public static final double FORCE_PN = 1200.0D;
    /** 单次横向力持续时长（游戏 tick）。 */
    public static final int PUSH_DURATION_TICKS = 10;
    /** 默认判定间隔：2 秒，运行时取 Config.TRAIN_SHAKE_INTERVAL_SECONDS。 */
    public static final int INTERVAL_TICKS = 40;
    /** 概率 = 速度 / 20，达到此速度即 100%。 */
    public static final double FULL_CHANCE_SPEED = 20.0D;

    /** 触发概率：|速度| / 20，钳在 [0, 1]。 */
    public static double triggerChance(double absSpeed) {
        return Math.max(0.0D, Math.min(1.0D, absSpeed / FULL_CHANCE_SPEED));
    }

    /**
     * 本次是否触发。
     *
     * @param roll 均匀分布 [0, 1) 随机数
     */
    public static boolean shouldTrigger(double absSpeed, double roll) {
        return roll < triggerChance(absSpeed);
    }

    /** 单个物理子步的冲量大小（pN·s）：力 × 子步时长，子步数量变化时总冲量不变。 */
    public static double impulsePerStep(double timeStep) {
        return impulsePerStep(timeStep, FORCE_PN);
    }

    public static double impulsePerStep(double timeStep, double forcePn) {
        return forcePn * timeStep;
    }

    public static int intervalTicks(double seconds) {
        return seconds <= 0.0D ? 0 : Math.max(1, (int) Math.round(seconds * 20.0D));
    }

    private TrainLateralForceMath() {
    }
}
