package dimblend.experience.compat.simurail;

/**
 * E7' 无人值守刹车 / E3 刹车音效速度门槛的纯函数。
 * 速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s）。
 */
public final class TrainSpeedGateMath {

    /** 任意车架超过此速度才开闸：刹车可播、无人值守扫描启动。用户口径「大于」不含等于。 */
    public static final double SPEED_THRESHOLD = 4.0D;

    public static boolean isFast(double absSpeed) {
        return absSpeed > SPEED_THRESHOLD;
    }

    /** E3 施闸音：仅速度大于门槛时播放。松闸（E4）不走此门。 */
    public static boolean isBrakeAudible(double absSpeed) {
        return isFast(absSpeed);
    }

    private TrainSpeedGateMath() {
    }
}
