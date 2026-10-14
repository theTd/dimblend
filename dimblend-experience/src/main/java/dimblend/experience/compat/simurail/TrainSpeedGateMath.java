package dimblend.experience.compat.simurail;

/**
 * E3 刹车音效速度门槛的纯函数。
 * 速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s）。
 */

public final class TrainSpeedGateMath {

    /** E3 施闸音：仅速度严格大于配置门槛时播放。松闸（E4）不走此门。 */
    public static boolean isBrakeAudible(double absSpeed, double speedThreshold) {
        return absSpeed > speedThreshold;
    }

    private TrainSpeedGateMath() {
    }
}