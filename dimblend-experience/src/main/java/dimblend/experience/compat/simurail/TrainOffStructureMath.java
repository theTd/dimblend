package dimblend.experience.compat.simurail;

/**
 * E7 离结构传送 / E3 刹车音效速度门槛的纯函数。
 * 速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s）。
 */
public final class TrainOffStructureMath {

    /** 任意车架超过此速度才开闸：刹车可播、离结构检测启动。用户口径「大于」不含等于。 */
    public static final double SPEED_THRESHOLD = 4.0D;
    /** 以玩家为中心的 sable 结构探测半径（格 / 米）。 */
    public static final double STRUCTURE_RADIUS = 32.0D;
    /** 检测间隔：10 秒。 */
    public static final int CHECK_INTERVAL_TICKS = 200;
    /**
     * A2 走廊条带半宽（格）：dimblend 预生成 z ∈ [-16, 15]。
     * 无床落点在 z=5，必须视为安全，否则全局闸仍开时每 10 秒反复 teleportTo。
     */
    public static final double CORRIDOR_SAFE_ABS_Z = 16.0D;

    public static boolean isFast(double absSpeed) {
        return absSpeed > SPEED_THRESHOLD;
    }

    /** E3 施闸音：仅速度大于门槛时播放。松闸（E4）不走此门。 */
    public static boolean isBrakeAudible(double absSpeed) {
        return isFast(absSpeed);
    }

    public static boolean isInCorridorSafeBand(double z) {
        return Math.abs(z) <= CORRIDOR_SAFE_ABS_Z;
    }

    /**
     * 是否应传送：有高速车架、不在 sable 结构旁、且不在走廊安全条带。
     * 走廊豁免锁住「无床落点下一周期不再传」。
     */
    public static boolean shouldTeleport(boolean anyFastBogey, boolean nearStructure,
                                         boolean inCorridorSafeBand) {
        return anyFastBogey && !nearStructure && !inCorridorSafeBand;
    }

    /**
     * 点到轴对齐包围盒的欧氏距离是否 ≤ radius（点在盒内为 0）。
     * 查询盒用立方体扩 radius 即可覆盖本球体，再由此收成真正半径。
     */
    public static boolean isWithinRadius(double px, double py, double pz,
                                         double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ,
                                         double radius) {
        double dx = px - clamp(px, minX, maxX);
        double dy = py - clamp(py, minY, maxY);
        double dz = pz - clamp(pz, minZ, maxZ);
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private TrainOffStructureMath() {
    }
}
