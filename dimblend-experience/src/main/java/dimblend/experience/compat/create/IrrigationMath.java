package dimblend.experience.compat.create;

/**
 * 分液池保湿/催熟的数值换算（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 * 调用方见 {@link ItemDrainIrrigation} 与 {@link ItemDrainGrowthBoost}。
 */
public final class IrrigationMath {

    /**
     * 保湿覆盖边界：以分液池为中心、边长 2r+1 的同层方形（y..y+1）。
     * 返回 {minX, minY, minZ, maxX, maxY, maxZ}。
     */
    public static double[] coverageBounds(int x, int y, int z, int radius) {
        return new double[]{x - radius, y, z - radius, x + radius + 1, y + 1, z + radius + 1};
    }

    /**
     * 覆盖内方块扫描的整数格范围（闭区间），与 {@link #coverageBounds} 一致。
     * 返回 {minX, y, minZ, maxX, maxZ}，调用方按 y 层遍历 minX..maxX × minZ..maxZ
     * （保湿扫 y 层耕地；催熟扫 y 与 y+1 层骨粉目标）。
     */
    public static int[] coverageCells(int x, int y, int z, int radius) {
        return new int[]{x - radius, y, z - radius, x + radius, z + radius};
    }

    /** 周期换算为调用节拍数（1 节拍 = 10 tick，由调用方 gameTime%10 降拍），向上取整保证周期不短于配置。 */
    public static int intervalBeats(int intervalTicks) {
        return Math.max(1, (intervalTicks + 9) / 10);
    }

    /** 存水是否低于停水阈值（等于阈值继续供水）。 */
    public static boolean belowThreshold(int amountMb, int minMb) {
        return amountMb < minMb;
    }

    private IrrigationMath() {
    }
}
